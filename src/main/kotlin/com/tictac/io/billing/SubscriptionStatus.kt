package com.tictac.io.billing

/**
 * The payment provider behind a customer or subscription. One today; the column exists so a
 * second needs no schema change, exactly as `user_identities.provider` does.
 */
enum class BillingProvider {
    STRIPE,
}

/**
 * How often the organization is billed for its licenses.
 *
 * This replaced `SubscriptionPlan` (FREE/PRO). **TicTac does not sell feature tiers** - every
 * organization has every feature, and the only thing money buys is licenses. What is left to
 * choose is therefore not *what* you get but *how often you pay for it*, which is exactly one
 * Stripe price each.
 *
 * The interval belongs to the subscription and never changes underneath a quantity change:
 * adding a license updates the item's quantity and leaves its price - and therefore its
 * interval - alone. Moving between monthly and annual is a price change, which is the billing
 * portal's job, not this backend's.
 */
enum class BillingInterval {
    MONTHLY,
    ANNUAL,
}

/**
 * The lifecycle of a subscription, mapped one-to-one from Stripe's own.
 *
 * Deliberately not a reinvented state machine. Stripe owns the transitions - it decides when
 * a payment retry turns `past_due` into `canceled` - so inventing a coarser local model would
 * only add a place for the two to disagree. Each constant below is exactly one Stripe status.
 */
enum class SubscriptionStatus {
    /** Awaiting a first successful payment. Nothing has been paid for yet. */
    INCOMPLETE,

    /** The first payment was never completed and Stripe gave up. Terminal. */
    INCOMPLETE_EXPIRED,

    TRIALING,
    ACTIVE,

    /** A renewal failed and Stripe is retrying. Still within the grace window. */
    PAST_DUE,

    /** Stripe stopped retrying but has not cancelled. Effectively unpaid. */
    UNPAID,

    /** Paused by a pause-collection schedule. Not billing, not cancelled. */
    PAUSED,

    /** Over. Reached either immediately or at the end of a cancelled period. */
    CANCELED,
    ;

    /**
     * Whether Stripe is currently billing this subscription.
     *
     * **This is a billing question and nothing else.** It is never a feature gate: TicTac sells
     * no paid features, so a CANCELED organization keeps every endpoint, every project and
     * every time entry it had. What this predicate decides is narrower - whether there is a
     * live billing relationship to add a license to, and whether the licenses it pays for are
     * currently being paid for.
     *
     * (It replaced `grantsAccess()`, which was named for a feature-entitlement model this
     * product does not have. Do not reintroduce one on top of it.)
     *
     * [PAST_DUE] counts, and that is the one judgement call in this file: Stripe is still
     * retrying the card and the customer has not done anything wrong yet. [UNPAID] does not -
     * that is Stripe having given up.
     */
    fun isBilling(): Boolean = this == ACTIVE || this == TRIALING || this == PAST_DUE

    companion object {
        /**
         * Stripe's status string to ours.
         *
         * ```
         * stripe               local
         * ------               -----
         * incomplete         → INCOMPLETE
         * incomplete_expired → INCOMPLETE_EXPIRED
         * trialing           → TRIALING
         * active             → ACTIVE
         * past_due           → PAST_DUE
         * unpaid             → UNPAID
         * paused             → PAUSED
         * canceled           → CANCELED
         * ```
         *
         * An unrecognised value throws rather than defaulting. Defaulting to ACTIVE would say
         * an organization is paying when it is not; defaulting to CANCELED would say a paying
         * customer has stopped. Failing makes the webhook return non-2xx, which Stripe
         * surfaces in its dashboard and retries - a loud, visible failure is the only honest
         * response to "Stripe invented a status we have never seen".
         */
        fun fromStripe(status: String?): SubscriptionStatus =
            when (status) {
                "incomplete" -> INCOMPLETE
                "incomplete_expired" -> INCOMPLETE_EXPIRED
                "trialing" -> TRIALING
                "active" -> ACTIVE
                "past_due" -> PAST_DUE
                "unpaid" -> UNPAID
                "paused" -> PAUSED
                "canceled" -> CANCELED
                else -> throw UnmappableStripeStateException("Unrecognised Stripe subscription status: $status")
            }
    }
}

/**
 * Stripe described something this backend cannot map - a status or a price it does not know.
 *
 * Always a loud failure. Billing state that is quietly wrong is worse than billing state that
 * is visibly stuck, because only one of the two gets noticed.
 */
class UnmappableStripeStateException(message: String) : RuntimeException(message)
