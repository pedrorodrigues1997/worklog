package com.tictac.io.billing

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Everything Stripe-specific, in one place.
 *
 * Price ids in particular: a `price_1234` written into a service would be wrong in every
 * environment but the one it was copied from, and unfindable when it needed changing. Here
 * they are configuration, and the code only ever speaks in [BillingInterval].
 *
 * There is **one price per interval and no tiers** - the same licence at the same per-unit
 * cost, billed monthly or annually. TicTac sells no feature plans, so a price is not a product
 * decision, only a billing cadence.
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

    /**
     * Billing interval to Stripe price id.
     *
     * Each must be an ordinary **per-unit** price: the quantity sent is the organization's
     * *paid* licences, which already excludes the free included one, so a graduated tier would
     * discount it twice.
     */
    val prices: Map<BillingInterval, String>,

    /** Where Stripe sends the browser afterwards. Fixed here, never taken from a request. */
    val successUrl: String,
    val cancelUrl: String,
    val portalReturnUrl: String,
) {
    /** Blank key means "not configured", which is the normal state locally. */
    val configured: Boolean get() = secretKey.isNotBlank()

    /** The price for [interval], or null if this deployment has none configured for it. */
    fun priceFor(interval: BillingInterval): String? = prices[interval]?.takeIf { it.isNotBlank() }

    /**
     * The interval a Stripe price id belongs to, for reading webhooks back.
     *
     * Returns null for a price this deployment does not know - which the webhook handler
     * treats as a loud failure rather than guessing. A price created in the Stripe dashboard
     * and never added here is a configuration mistake, and the alternative to failing is
     * recording a billing cadence nobody chose.
     */
    fun intervalFor(priceId: String?): BillingInterval? =
        priceId?.let { id -> prices.entries.firstOrNull { it.value == id }?.key }

    init {
        require(!configured || webhookSecret.isNotBlank()) {
            "billing.stripe.webhook-secret is required when a secret key is set - " +
                "without it webhook signatures cannot be verified and the endpoint would accept anything"
        }
    }
}
