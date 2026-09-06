package com.tictac.io.billing

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/**
 * How many licences the organization should hold, in total.
 *
 * The whole number, never a delta: sending the same value twice does nothing the second time,
 * and a count that has drifted is corrected rather than compounded.
 *
 * The floor is `1` - the free included licence always exists, and an organization always has
 * at least its owner in it, so there is no such thing as zero licences. Giving up the last
 * *paid* licence means asking for `1`, which cancels the subscription.
 */
data class LicenseCountRequest(
    @field:NotNull(message = "Licence count is required")
    @field:Min(value = 1, message = "An organization always holds at least one licence")
    @field:Max(value = 1000, message = "At most 1000 licences can be held")
    val licenses: Long?,
)

/**
 * The organization's first licence purchase.
 *
 * Both fields are genuine choices and neither can be inferred: how many licences to buy, and
 * whether to be billed monthly or annually. The *price* is not a choice - it comes from
 * configuration - so there is nothing here a client could use to pay less than it owes.
 */
data class CheckoutRequest(
    @field:NotNull(message = "Licence count is required")
    @field:Min(value = 2, message = "The first licence is included free; checkout buys the second and beyond")
    @field:Max(value = 1000, message = "At most 1000 licences can be held")
    val licenses: Long?,

    @field:NotNull(message = "Billing interval is required")
    val interval: BillingInterval?,
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
    /** Licences the organization will hold once this session completes. */
    val licenseCount: Long,
    /** Licences Stripe will bill for: one fewer, because the first is included. */
    val paidLicenses: Long,
    val interval: BillingInterval,
)

data class BillingPortalSessionResponse(
    val url: String,
)

/**
 * The organization's licences: how many it holds, who is in them, and what is billed.
 *
 * Deliberately separate from [SubscriptionResponse]. A licence is a seat the organization
 * owns; a subscription is its billing relationship with Stripe. They are usually consistent
 * and occasionally are not - after a dashboard edit, or once a subscription has ended - and
 * collapsing them into one object would hide exactly the case somebody needs to see.
 */
data class LicenseResponse(
    val organizationId: UUID,
    /** Licences the organization holds. */
    val licenseCount: Long,
    /** Members, each occupying exactly one licence. */
    val membersOccupying: Long,
    /** Licences with nobody in them: `licenseCount - membersOccupying`. */
    val vacantLicenses: Long,
    /** Invitations still outstanding. Each is holding a licence until it is accepted or lapses. */
    val pendingInvitations: Long,
    /** Licences free to allocate right now: vacant, minus the ones invitations are holding. */
    val licensesAvailable: Long,
    /** Licences that cost money: `licenseCount - 1`, because the first is included. */
    val paidLicenses: Long,
    /** What Stripe was last told. Differs from [paidLicenses] only when something has drifted. */
    val billedLicenses: Long,
    /** Null until the organization has ever subscribed. */
    val billingInterval: BillingInterval?,
    /** Null until the organization has ever subscribed. */
    val subscriptionStatus: SubscriptionStatus?,
) {
    companion object {
        fun from(organizationId: UUID, state: LicenseState) =
            LicenseResponse(
                organizationId = organizationId,
                licenseCount = state.licenseCount,
                membersOccupying = state.membersOccupying,
                vacantLicenses = state.vacantLicenses,
                pendingInvitations = state.pendingInvitations,
                licensesAvailable = state.licensesAvailable,
                paidLicenses = state.paidLicenses,
                billedLicenses = state.billedLicenses,
                billingInterval = state.billingInterval,
                subscriptionStatus = state.subscriptionStatus,
            )
    }
}

/**
 * The organization's Stripe billing relationship.
 *
 * Application-level facts only. No Stripe customer id, no subscription id, no price id, and
 * certainly no key - a client needs to know what it is paying for and until when, and none of
 * Stripe's internal identifiers help with that.
 *
 * There is **no plan field**. TicTac sells no feature tiers, so there is nothing for a plan to
 * name; what a customer chooses is the billing interval, which is [billingInterval].
 *
 * [billing] is [SubscriptionStatus.isBilling] pre-computed, so a client does not have to
 * reimplement which statuses count. It says whether Stripe is charging - **never** whether the
 * organization may use the product, which is always yes.
 */
data class SubscriptionResponse(
    val organizationId: UUID,
    val status: SubscriptionStatus?,
    /** Whether Stripe is currently billing. Not a feature gate - there are no paid features. */
    val billing: Boolean,
    /** Null until the organization has ever subscribed. */
    val billingInterval: BillingInterval?,
    /** Licences Stripe is billing for: the subscription's quantity. */
    val billedLicenses: Long,
    /** Licences the organization holds, the free included one counted. */
    val licenseCount: Long,
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
         * Every organization has a billing state from the moment it exists, and it holds its
         * one free licence whether or not Stripe has ever heard of it.
         */
        fun unsubscribed(organizationId: UUID, licenseCount: Long) =
            SubscriptionResponse(
                organizationId = organizationId,
                status = null,
                billing = false,
                billingInterval = null,
                billedLicenses = 0,
                licenseCount = licenseCount,
                currentPeriodStart = null,
                currentPeriodEnd = null,
                cancelAtPeriodEnd = false,
                cancelledAt = null,
            )
    }
}
