package com.tictac.io.billing

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/**
 * What to buy. A plan, not a price id: the client never sees or sends a Stripe identifier,
 * so a price can be replaced in Stripe without any client changing.
 *
 * [seatQuantity] is what the organization chooses to pay for, and is deliberately not derived
 * from the current member count. Seat enforcement does not exist yet, and inventing it here -
 * silently charging for however many people happen to be in the organization this minute -
 * would be a pricing decision made by a default.
 */
data class StartCheckoutRequest(
    @field:NotNull(message = "Plan is required")
    val plan: SubscriptionPlan?,

    @field:Min(value = 1, message = "At least one seat is required")
    @field:Max(value = 1000, message = "At most 1000 seats can be purchased at once")
    val seatQuantity: Int = 1,
)

/**
 * Where to send the browser. [sessionId] is included for clients using Stripe.js rather than
 * a plain redirect.
 *
 * Note what this does *not* say: nothing about the subscription being active. A checkout
 * session is a payment form that has been prepared, and Stripe has confirmed nothing yet.
 */
data class CheckoutSessionResponse(
    val sessionId: String,
    val url: String,
)

data class BillingPortalSessionResponse(
    val url: String,
)

/**
 * The organization's billing state.
 *
 * Application-level facts only. No Stripe customer id, no subscription id, no price id, and
 * certainly no key - a client needs to know what it is paying for and until when, and none of
 * Stripe's internal identifiers help with that.
 *
 * [active] is [SubscriptionStatus.grantsAccess] pre-computed, so a client does not have to
 * reimplement which statuses count.
 */
data class SubscriptionResponse(
    val organizationId: UUID,
    val plan: SubscriptionPlan,
    val status: SubscriptionStatus,
    val active: Boolean,
    val seatQuantity: Int,
    val currentPeriodStart: Instant?,
    val currentPeriodEnd: Instant?,
    /** True when the subscription is paid up but will not renew. */
    val cancelAtPeriodEnd: Boolean,
    /** When cancellation was *requested*, not when access ends. */
    val cancelledAt: Instant?,
) {
    companion object {
        /**
         * An organization that has never subscribed.
         *
         * A state, not an absence - which is why the endpoint returns this rather than a 404.
         * Every organization has a billing state from the moment it exists.
         */
        fun free(organizationId: UUID) =
            SubscriptionResponse(
                organizationId = organizationId,
                plan = SubscriptionPlan.DEFAULT,
                status = SubscriptionStatus.ACTIVE,
                active = true,
                seatQuantity = 0,
                currentPeriodStart = null,
                currentPeriodEnd = null,
                cancelAtPeriodEnd = false,
                cancelledAt = null,
            )
    }
}
