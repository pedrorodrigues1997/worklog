package com.tictac.io.billing

import com.tictac.io.organization.OrganizationAccess
import com.tictac.io.organization.OrganizationContext
import com.tictac.io.organization.OrganizationRole
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** The price for this billing interval is not configured, so there is nothing to buy. */
class IntervalNotPurchasableException(interval: BillingInterval) :
    RuntimeException("No price is configured for $interval billing")

/**
 * The organization already has a subscription.
 *
 * Checkout creates one, and there is only ever one: its job is to capture a card, which an
 * organization already paying has given. Changing how many licences it buys is
 * `PUT /licenses`; changing the card itself is the billing portal's.
 */
class AlreadySubscribedException :
    RuntimeException(
        "This organization already has a subscription; change its licence count instead of checking out again",
    )

/**
 * Starting checkout, opening the billing portal, and reading billing state.
 *
 * **Nothing here activates a subscription.** Creating a Checkout Session means a person is
 * about to be shown a payment form, and that is all it means; the subscription - and the
 * licence count it pays for - becomes real when Stripe says so over a signed webhook. See
 * [StripeWebhookService].
 *
 * Everything is scoped through [OrganizationAccess], so the organization id in the URL is only
 * ever half of a membership lookup whose other half comes from the token. There is no path by
 * which one organization reaches another's customer, subscription or portal.
 *
 * **Who may do what**, and the split is deliberate:
 *
 * - **OWNER or ADMIN** may buy and change licences. An administrator who can invite somebody
 *   can already cause a licence to be acquired, so denying them the explicit operation would
 *   be a distinction without a difference.
 * - **OWNER only** may open the billing portal. That is cards, invoices and cancellation - the
 *   payment instrument itself, not the headcount - and it stays with the person who owns the
 *   company's relationship with Stripe.
 * - **Any member** may read. Licence counts and renewal dates are facts about the company that
 *   everyone working there has a reasonable interest in, and none of it is a secret.
 */
@Service
class BillingService(
    private val organizationAccess: OrganizationAccess,
    private val billingCustomerRepository: BillingCustomerRepository,
    private val organizationLicenseService: OrganizationLicenseService,
    private val subscriptionRepository: SubscriptionRepository,
    private val stripeGateway: StripeGateway,
    private val properties: BillingProperties,
) {

    /**
     * The organization's **first** licence purchase. OWNER or ADMIN.
     *
     * Checkout exists to capture a card, and that happens once in an organization's life.
     * Every later change - more licences, fewer licences - is [changeLicenseCount], which
     * charges the saved card with no checkout and no redirect.
     *
     * The licence count is written **only** by the webhook that confirms the purchase, never
     * here: a prepared payment form is not a payment, and an organization that abandons
     * checkout must not come away holding licences nobody paid for.
     *
     * The Stripe call happens outside any transaction of ours by design. It is a network round
     * trip to a third party; holding a database transaction open across it would pin a
     * connection for however long Stripe takes.
     */
    fun startCheckout(organizationId: UUID, request: CheckoutRequest): CheckoutSessionResponse {
        val context = organizationAccess.require(organizationId, OrganizationRole.OWNER, OrganizationRole.ADMIN)

        // Before the price lookup, deliberately. With billing unconfigured every price is
        // blank too, so checking that first would answer "that interval cannot be bought" to a
        // deployment whose real problem is that it has no Stripe key at all.
        requireBillingConfigured()

        // Exactly one subscription per organization. A second checkout would create a second,
        // which is the thing this model exists to prevent - licences grow on the existing one.
        subscriptionRepository.findByOrganizationId(context.organizationId)
            ?.takeIf { it.isResizable() }
            ?.let { throw AlreadySubscribedException() }

        // Safe: both validated by the controller.
        val licenses = request.licenses!!
        val interval = request.interval!!

        // Cannot buy fewer licences than the organization's members already occupy - that
        // would put it over capacity the moment it started paying.
        val members = organizationLicenseService.stateOf(context.organization).membersOccupying
        val minimum = OrganizationLicenses.minimumLicensesFor(members)
        if (licenses < minimum) {
            throw LicensesOccupiedException(licensesRequested = licenses, minimumLicenses = minimum)
        }

        val price = properties.priceFor(interval) ?: throw IntervalNotPurchasableException(interval)
        val paidLicenses = OrganizationLicenses.paidLicensesFor(licenses)
        val customerId = findOrCreateCustomer(context)

        val session = stripeGateway.createCheckoutSession(
            customerId = customerId,
            priceId = price,
            organizationId = context.organizationId,
            quantity = paidLicenses,
        )

        return CheckoutSessionResponse(
            sessionId = session.id,
            url = session.url,
            licenseCount = licenses,
            paidLicenses = paidLicenses,
            interval = interval,
        )
    }

    /**
     * Sets how many licences the organization holds. OWNER or ADMIN.
     *
     * **No checkout, and no card is re-entered.** Stripe charges the payment method already on
     * file and prorates for the rest of the period - monthly or annual alike, and using
     * Stripe's own proration rather than any arithmetic of ours.
     */
    fun changeLicenseCount(organizationId: UUID, request: LicenseCountRequest): LicenseResponse {
        val context = organizationAccess.require(organizationId, OrganizationRole.OWNER, OrganizationRole.ADMIN)
        requireBillingConfigured()

        // Safe: validated by the controller.
        val organization = organizationLicenseService.changeLicenseCount(context.organizationId, request.licenses!!)

        return LicenseResponse.from(context.organizationId, organizationLicenseService.stateOf(organization))
    }

    /**
     * The organization's licences, readable by any member.
     *
     * Separate from [getSubscription] on purpose: this is what the company holds and who is in
     * it, which is an organization fact. The subscription is the Stripe relationship behind it.
     */
    @Transactional(readOnly = true)
    fun getLicenses(organizationId: UUID): LicenseResponse {
        val context = organizationAccess.require(organizationId)

        return LicenseResponse.from(
            context.organizationId,
            organizationLicenseService.stateOf(context.organization),
        )
    }

    /** Opens Stripe's billing portal for the organization's customer. OWNER only. */
    fun openBillingPortal(organizationId: UUID): BillingPortalSessionResponse {
        val context = organizationAccess.require(organizationId, OrganizationRole.OWNER)

        requireBillingConfigured()

        val customerId = findOrCreateCustomer(context)
        val session = stripeGateway.createPortalSession(customerId)

        return BillingPortalSessionResponse(url = session.url)
    }

    /**
     * The organization's subscription, readable by any member.
     *
     * An organization that has never subscribed has no row, and gets an unsubscribed state
     * rather than a 404: "no subscription" is a billing state, not a missing resource. It
     * still holds its free included licence, which is why [SubscriptionResponse.licenseCount]
     * is never zero.
     */
    @Transactional(readOnly = true)
    fun getSubscription(organizationId: UUID): SubscriptionResponse {
        val context = organizationAccess.require(organizationId)
        val licenseCount = context.organization.licenseCount.toLong()
        val subscription = subscriptionRepository.findByOrganizationId(context.organizationId)

        return subscription?.toResponse(licenseCount)
            ?: SubscriptionResponse.unsubscribed(context.organizationId, licenseCount)
    }

    /**
     * The organization's Stripe customer, creating it only the first time.
     *
     * Reused across checkout, the portal, future invoices and payment methods - which is the
     * whole reason it is stored rather than minted per session. Creating one per checkout
     * attempt would litter the Stripe account with duplicate customers and split one
     * company's billing history across all of them.
     *
     * Two concurrent first-time checkouts both find nothing and both create a customer at
     * Stripe; the unique index on `(organization_id, provider)` lets only one row win, and the
     * loser re-reads the winner's. That leaves one orphaned Stripe customer with no
     * subscription attached, which is harmless and vastly preferable to holding a lock across
     * a third-party call.
     */
    private fun findOrCreateCustomer(context: OrganizationContext): String {
        existingCustomer(context.organizationId)?.let { return it }

        val customerId = stripeGateway.createCustomer(context.organizationId, context.organization.name)

        return try {
            saveCustomer(context.organizationId, customerId)
        } catch (ex: DataIntegrityViolationException) {
            // Lost the race. The winner's customer is the one that counts from here on.
            existingCustomer(context.organizationId)
                ?: throw StripeUnavailableException("Could not establish a billing customer", ex)
        }
    }

    /**
     * Refuses early when this deployment has no Stripe configuration.
     *
     * [StripeGateway] refuses too, and is the real guard - but only once a call reaches it,
     * by which point a misleading error may already have been produced.
     */
    private fun requireBillingConfigured() {
        if (!properties.configured) throw BillingNotConfiguredException()
    }

    /**
     * Deliberately un-annotated, both of them.
     *
     * `@Transactional` here would be a lie: they are called from [findOrCreateCustomer] on
     * `this`, which goes straight past the proxy that implements it. Spring Data's own
     * repository methods are transactional, which is all these need - and each is a single
     * statement, so there is nothing to make atomic across them anyway. The one thing that
     * must not happen is a transaction spanning the Stripe call between them.
     */
    private fun existingCustomer(organizationId: UUID): String? =
        billingCustomerRepository
            .findByOrganizationIdAndProvider(organizationId, BillingProvider.STRIPE)
            ?.providerCustomerId

    private fun saveCustomer(organizationId: UUID, customerId: String): String =
        billingCustomerRepository.saveAndFlush(
            BillingCustomer(
                organizationId = organizationId,
                provider = BillingProvider.STRIPE,
                providerCustomerId = customerId,
            ),
        ).providerCustomerId
}

internal fun Subscription.toResponse(licenseCount: Long) =
    SubscriptionResponse(
        organizationId = organizationId,
        status = status,
        billing = status.isBilling(),
        billingInterval = billingInterval,
        billedLicenses = paidLicenses.toLong(),
        licenseCount = licenseCount,
        currentPeriodStart = currentPeriodStart,
        currentPeriodEnd = currentPeriodEnd,
        cancelAtPeriodEnd = cancelAtPeriodEnd,
        cancelledAt = cancelledAt,
    )
