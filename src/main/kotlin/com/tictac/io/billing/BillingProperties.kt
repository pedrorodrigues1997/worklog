package com.tictac.io.billing

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Everything Stripe-specific, in one place.
 *
 * Price ids in particular: a `price_1234` written into a service would be wrong in every
 * environment but the one it was copied from, and unfindable when it needed changing. Here
 * they are configuration, and the code only ever speaks in [SubscriptionPlan].
 *
 * Entirely optional. With no secret key the application still boots and every non-billing
 * feature works - a developer must be able to clone and run without a Stripe account, the
 * same call the OAuth configuration makes. Billing endpoints then refuse with a clear error
 * rather than the application refusing to start.
 */
@ConfigurationProperties(prefix = "billing.stripe")
data class BillingProperties(
    /** `sk_...`. Never logged, never returned, never sent to a client. */
    val secretKey: String,

    /** `whsec_...`. The shared secret every webhook signature is checked against. */
    val webhookSecret: String,

    /** Plan to Stripe price id. A plan with no price cannot be checked out. */
    val prices: Map<SubscriptionPlan, String>,

    /** Where Stripe sends the browser afterwards. Fixed here, never taken from a request. */
    val successUrl: String,
    val cancelUrl: String,
    val portalReturnUrl: String,
) {
    /** Blank key means "not configured", which is the normal state locally. */
    val configured: Boolean get() = secretKey.isNotBlank()

    /**
     * The price to charge for [plan], or null if it has none configured.
     *
     * [SubscriptionPlan.FREE] deliberately has no price: there is nothing to check out.
     */
    fun priceFor(plan: SubscriptionPlan): String? = prices[plan]?.takeIf { it.isNotBlank() }

    /**
     * The plan a Stripe price id belongs to, for reading webhooks back.
     *
     * Returns null for a price this deployment does not know - which the webhook handler
     * treats as a loud failure rather than guessing a plan. A price created in the Stripe
     * dashboard and never added here is a configuration mistake, and the alternative to
     * failing is billing somebody for a plan nobody chose.
     */
    fun planFor(priceId: String?): SubscriptionPlan? =
        priceId?.let { id -> prices.entries.firstOrNull { it.value == id }?.key }

    init {
        require(!configured || webhookSecret.isNotBlank()) {
            "billing.stripe.webhook-secret is required when a secret key is set - " +
                "without it webhook signatures cannot be verified and the endpoint would accept anything"
        }
    }
}
