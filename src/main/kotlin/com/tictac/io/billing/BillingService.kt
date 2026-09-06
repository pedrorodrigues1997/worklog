package com.tictac.io.billing

import com.tictac.io.organization.OrganizationAccess
import com.tictac.io.organization.OrganizationContext
import com.tictac.io.organization.OrganizationRole
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** The requested plan has no Stripe price configured, so there is nothing to buy. */
class PlanNotPurchasableException(plan: SubscriptionPlan) :
    RuntimeException("Plan $plan cannot be purchased")

/**
 * Starting checkout, opening the billing portal, and reading the current state.
 *
 * **Nothing here activates a subscription.** Creating a Checkout Session means a person is
 * about to be shown a payment form, and that is all it means; the subscription becomes real
 * when Stripe says so over a signed webhook. See [StripeWebhookService].
 */
@Service
class BillingService(
    private val organizationAccess: OrganizationAccess,
    private val billingCustomerRepository: BillingCustomerRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val stripeGateway: StripeGateway,
    private val properties: BillingProperties,
) {

    /**
     * Starts a subscription checkout. OWNER only.
     *
     * The Stripe call happens outside any transaction of ours by design. It is a network
     * round trip to a third party; holding a database transaction open across it would pin a
     * connection for however long Stripe takes, and Stripe's own timeout would then decide
     * how long this application holds locks. The only local write - the customer row - is
     * committed in its own short transaction before the session is created.
     */
    fun startCheckout(organizationId: UUID, request: StartCheckoutRequest): CheckoutSessionResponse {
        val context = organizationAccess.require(organizationId, OrganizationRole.OWNER)

        // Before the price lookup, deliberately. With billing unconfigured *every* plan has
        // a blank price, so checking the price first would answer "that plan cannot be
        // bought" to a deployment whose real problem is that it has no Stripe key at all.
        requireBillingConfigured()

        // Safe: validated as @NotNull by the controller.
        val plan = request.plan!!
        val price = properties.priceFor(plan) ?: throw PlanNotPurchasableException(plan)

        val customerId = findOrCreateCustomer(context)

        val session = stripeGateway.createCheckoutSession(
            customerId = customerId,
            priceId = price,
            organizationId = context.organizationId,
            seatQuantity = request.seatQuantity.toLong(),
        )

        return CheckoutSessionResponse(sessionId = session.id, url = session.url)
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
     * The organization's billing state, readable by any member.
     *
     * Plan, seats and renewal date are facts about the company that everyone working there
     * has a reasonable interest in - and none of it is a secret or a Stripe identifier. Only
     * *changing* billing is restricted.
     *
     * An organization that has never subscribed has no row, and gets the FREE default rather
     * than a 404: "no subscription" is a billing state, not a missing resource.
     */
    @Transactional(readOnly = true)
    fun getSubscription(organizationId: UUID): SubscriptionResponse {
        val context = organizationAccess.require(organizationId)
        val subscription = subscriptionRepository.findByOrganizationId(context.organizationId)

        return subscription?.toResponse() ?: SubscriptionResponse.free(context.organizationId)
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

internal fun Subscription.toResponse() =
    SubscriptionResponse(
        organizationId = organizationId,
        plan = plan,
        status = status,
        active = status.grantsAccess(),
        seatQuantity = seatQuantity,
        currentPeriodStart = currentPeriodStart,
        currentPeriodEnd = currentPeriodEnd,
        cancelAtPeriodEnd = cancelAtPeriodEnd,
        cancelledAt = cancelledAt,
    )
