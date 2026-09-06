package com.tictac.io.billing

/**
 * The payment provider behind a customer or subscription. One today; the column exists so a
 * second needs no schema change, exactly as `user_identities.provider` does.
 */
enum class BillingProvider {
    STRIPE,
}

/** The plans TicTac sells. Stripe price ids live in configuration, never here. */
enum class SubscriptionPlan {
    /** No subscription, or one that has lapsed. The state every organization starts in. */
    FREE,

    PRO,
    ;

    companion object {
        /** The plan an organization has when nothing else is true of it. */
        val DEFAULT = FREE
    }
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
     * Whether this status means the organization is currently entitled to paid features.
     *
     * [PAST_DUE] counts, and that is the one judgement call in this file: Stripe is still
     * retrying the card, the customer has not done anything wrong yet, and cutting a paying
     * team off the instant a renewal blips would be worse than carrying them for the few days
     * of the retry window. [UNPAID] does not count - that is Stripe having given up.
     *
     * Nothing enforces entitlement yet; this is the predicate feature-gating will use, kept
     * here so the answer is defined in one place when it does.
     */
    fun grantsAccess(): Boolean = this == ACTIVE || this == TRIALING || this == PAST_DUE

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
         * An unrecognised value throws rather than defaulting. Defaulting to ACTIVE would
         * hand out paid features on a status nobody has read; defaulting to CANCELED would
         * cut off a paying customer. Failing makes the webhook return non-2xx, which Stripe
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
