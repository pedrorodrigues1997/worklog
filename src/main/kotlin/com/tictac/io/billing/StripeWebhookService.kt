package com.tictac.io.billing

import com.stripe.Stripe
import com.stripe.exception.EventDataObjectDeserializationException
import com.stripe.model.Event
import com.stripe.model.StripeObject
import com.stripe.model.Subscription as StripeSubscription
import com.tictac.io.organization.OrganizationRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * What a delivered webhook did, for the controller to answer Stripe with.
 *
 * [Applied] and [Ignored] are both 200s - Stripe should stop retrying either way. Only a
 * thrown exception produces a non-2xx and asks Stripe to try again.
 */
sealed interface WebhookOutcome {
    data class Applied(val organizationId: UUID) : WebhookOutcome
    data class Ignored(val reason: String) : WebhookOutcome
}

/**
 * Turning Stripe's account of the world into local subscription state.
 *
 * Three properties matter more than the mapping itself:
 *
 * 1. **Idempotency.** Stripe delivers at least once. The event id is inserted into
 *    `billing_webhook_events` in the same transaction as the state change, so a retry
 *    violates the unique index and rolls the whole thing back having changed nothing.
 * 2. **The organization is resolved from the Stripe customer, never from metadata.** Metadata
 *    is set by us but editable by anyone with Stripe dashboard access, so treating it as an
 *    authorisation input would let a dashboard user move a subscription between tenants.
 *    `billing_customers` is the mapping, and it was written when we created the customer.
 * 3. **Unmappable input fails loudly.** A status or a price this deployment does not
 *    recognise throws, the webhook returns non-2xx, and Stripe surfaces it. Guessing would
 *    mean either handing out paid features or cutting off a paying customer, silently.
 */
@Service
class StripeWebhookService(
    private val subscriptionRepository: SubscriptionRepository,
    private val billingCustomerRepository: BillingCustomerRepository,
    private val billingWebhookEventRepository: BillingWebhookEventRepository,
    private val organizationRepository: OrganizationRepository,
    private val properties: BillingProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Records the event and applies it, atomically.
     *
     * One transaction covering both is the whole idempotency mechanism: there is no window in
     * which an event is marked handled but its effect is missing, or applied but not marked.
     *
     * The transaction contains no network calls. Everything needed is already in the payload
     * Stripe signed, which is why the subscription object is read from the event rather than
     * re-fetched - a re-fetch would put a third-party round trip inside this transaction and
     * would also let the answer change between the signature and the write.
     */
    @Transactional
    fun handle(event: Event): WebhookOutcome {
        val alreadyHandled = billingWebhookEventRepository
            .findByProviderAndEventId(BillingProvider.STRIPE, event.id) != null

        if (alreadyHandled) {
            // The ordinary case for a retry: nothing to do, and Stripe should stop asking.
            return WebhookOutcome.Ignored("already processed")
        }

        try {
            billingWebhookEventRepository.saveAndFlush(
                BillingWebhookEvent(
                    provider = BillingProvider.STRIPE,
                    eventId = event.id,
                    eventType = event.type,
                ),
            )
        } catch (ex: DataIntegrityViolationException) {
            // Two deliveries of the same event arriving together. The index decided; this one
            // loses and its transaction rolls back having applied nothing.
            return WebhookOutcome.Ignored("already processed")
        }

        return apply(event)
    }

    /**
     * Only the events that change subscription state.
     *
     * `checkout.session.completed` is deliberately not among them. It would tell us a
     * checkout finished, but the customer-to-organization mapping it could establish already
     * exists - this backend created that customer before the session - and
     * `customer.subscription.created` carries the authoritative subscription state moments
     * later. Handling both would mean two paths writing the same row for one purchase.
     *
     * Everything else is recorded and ignored: a 200, because Stripe retrying an event we
     * will never act on is pure noise.
     */
    private fun apply(event: Event): WebhookOutcome =
        when (event.type) {
            SUBSCRIPTION_CREATED, SUBSCRIPTION_UPDATED, SUBSCRIPTION_DELETED ->
                applySubscription(event)

            else -> WebhookOutcome.Ignored("unhandled event type ${event.type}")
        }

    private fun applySubscription(event: Event): WebhookOutcome {
        val stripeSubscription = deserialize(event) as? StripeSubscription
            ?: throw UnmappableStripeStateException("Event ${event.id} carried no subscription object")

        val customerId = stripeSubscription.customer
            ?: throw UnmappableStripeStateException("Subscription ${stripeSubscription.id} has no customer")

        // The mapping, and the only one. An event about a customer this deployment has never
        // created is not an error - it is a Stripe account shared with something else, or a
        // test event - so it is recorded and ignored rather than retried forever.
        val billingCustomer = billingCustomerRepository
            .findByProviderAndProviderCustomerId(BillingProvider.STRIPE, customerId)
            ?: run {
                log.warn("Stripe subscription event for unknown customer {}; ignoring", customerId)
                return WebhookOutcome.Ignored("unknown Stripe customer")
            }

        val organizationId = billingCustomer.organizationId

        // Locked before reading anything we intend to write: two events about the same
        // organization arriving together would otherwise both read and both write.
        val existing = subscriptionRepository.findAndLockByOrganizationId(organizationId)

        // The subscription must not be one already claimed by a *different* organization.
        // Unreachable through Stripe's own data - a subscription has one customer - but the
        // unique index would refuse it anyway, and failing here says why.
        stripeSubscription.id?.let { subscriptionId ->
            val claimedElsewhere = subscriptionRepository
                .findByProviderAndProviderSubscriptionId(BillingProvider.STRIPE, subscriptionId)
                ?.takeIf { it.organizationId != organizationId }

            if (claimedElsewhere != null) {
                throw UnmappableStripeStateException(
                    "Stripe subscription $subscriptionId is already held by another organization",
                )
            }
        }

        // The first (and, in our model, only) line item carries everything that matters:
        // the price the billing interval is read from, the paid-licence quantity, and the
        // period bounds. A subscription without one cannot be mapped to anything, so it fails
        // rather than writing a row with a guessed interval and null dates.
        //
        // Additional items are ignored deliberately: TicTac sells one price per subscription,
        // and quietly summing several would invent a licence count nobody agreed to.
        val item = stripeSubscription.items?.data?.firstOrNull()
            ?: throw UnmappableStripeStateException(
                "Stripe subscription ${stripeSubscription.id} has no line items",
            )

        val priceId = item.price?.id
        // The interval comes from the price, which is the only thing that knows it. An
        // unconfigured price throws rather than defaulting: recording MONTHLY for an annual
        // subscription would misstate what the customer bought, and by a factor of twelve.
        val interval = properties.intervalFor(priceId)
            ?: throw UnmappableStripeStateException("No billing interval configured for Stripe price $priceId")

        val quantity = item.quantity?.toInt()

        val subscription = existing ?: Subscription(
            organizationId = organizationId,
            billingInterval = interval,
            status = SubscriptionStatus.fromStripe(stripeSubscription.status),
            paidLicenses = quantity ?: 0,
            provider = BillingProvider.STRIPE,
        )

        subscription.billingInterval = interval
        subscription.status = SubscriptionStatus.fromStripe(stripeSubscription.status)
        subscription.paidLicenses = quantity ?: subscription.paidLicenses
        subscription.providerSubscriptionId = stripeSubscription.id
        // Quantity lives on the item, so changing the licence count later needs this id.
        // Recorded here because the webhook is the only thing that knows it without asking
        // Stripe.
        subscription.providerItemId = item.id
        // Period bounds live on the subscription *item* in the current Stripe API, not on the
        // subscription - they moved, and reading them off the wrong object silently yields
        // nulls rather than an error.
        subscription.currentPeriodStart = item.currentPeriodStart?.let(Instant::ofEpochSecond)
        subscription.currentPeriodEnd = item.currentPeriodEnd?.let(Instant::ofEpochSecond)
        // Stripe's canceled_at is when cancellation was *requested*. For "cancel at period
        // end" it is set while the subscription is still active and still paid for, which is
        // exactly why the flag below is stored separately rather than inferred from this.
        subscription.cancelledAt = stripeSubscription.canceledAt?.let(Instant::ofEpochSecond)
        subscription.cancelAtPeriodEnd = stripeSubscription.cancelAtPeriodEnd ?: false

        subscriptionRepository.saveAndFlush(subscription)

        // Stripe is authoritative about the quantity, so the organization's licence count
        // follows it - which is what makes a quantity edited in the Stripe dashboard, or a
        // checkout completing, arrive here rather than being invented locally.
        syncLicenseCount(organizationId, subscription)

        return WebhookOutcome.Applied(organizationId)
    }

    /**
     * Brings the organization's licence count up to what Stripe is billing for.
     *
     * `paid_licenses + 1`, because the first licence is included free. This is how a completed
     * checkout turns into licences the organization actually holds - nothing local writes them
     * at checkout time, because a prepared payment form is not a payment.
     *
     * **Raised only, never lowered.** A subscription ending - CANCELED, UNPAID - would
     * otherwise silently strip an organization of licences its members are sitting in, and
     * turning people out because a card expired is precisely what must not happen. What the
     * organization *holds* is its own record and survives; what it is *billed* for is
     * `paid_licenses`, and any gap between the two is visible in the licence API.
     *
     * A deliberate reduction goes through [OrganizationLicenseService.changeLicenseCount],
     * which writes the local count itself and does not need this to lower anything.
     */
    private fun syncLicenseCount(organizationId: UUID, subscription: Subscription) {
        if (!subscription.status.isBilling()) return

        val licensesBilledFor = subscription.paidLicenses + OrganizationLicenses.INCLUDED_LICENSES
        val organization = organizationRepository.findAndLockById(organizationId) ?: return

        if (organization.licenseCount >= licensesBilledFor) return

        organization.licenseCount = licensesBilledFor.toInt()
        organizationRepository.saveAndFlush(organization)

        log.info("Organization {} now holds {} licence(s), per Stripe", organizationId, licensesBilledFor)
    }

    /**
     * The event's payload object, tolerating an API-version mismatch.
     *
     * Stripe's deserializer returns nothing when the event's `api_version` differs from the
     * one this SDK was built against - and that is a normal production state, not an error:
     * a Stripe account is pinned to an API version independently of when we upgrade the
     * library, so any upgrade on either side puts them briefly out of step.
     *
     * Refusing those events would mean billing silently stopping updating after a routine
     * dependency bump. `deserializeUnsafe` reads the payload anyway, which is Stripe's own
     * documented answer; the warning is there so the skew is visible rather than permanent.
     * The fields this service reads - status, customer, items, cancellation - are long-stable,
     * and anything genuinely unreadable still throws below.
     */
    private fun deserialize(event: Event): StripeObject? {
        val deserializer = event.dataObjectDeserializer

        deserializer.`object`.orElse(null)?.let { return it }

        log.warn(
            "Stripe event {} has API version {} but this SDK expects {}; deserializing anyway",
            event.id,
            event.apiVersion,
            Stripe.API_VERSION,
        )

        return try {
            deserializer.deserializeUnsafe()
        } catch (ex: EventDataObjectDeserializationException) {
            throw UnmappableStripeStateException("Event ${event.id} payload could not be read: ${ex.message}")
        }
    }

    private companion object {
        const val SUBSCRIPTION_CREATED = "customer.subscription.created"
        const val SUBSCRIPTION_UPDATED = "customer.subscription.updated"
        const val SUBSCRIPTION_DELETED = "customer.subscription.deleted"
    }
}
