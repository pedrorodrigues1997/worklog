package com.tictac.io.billing

/**
 * The licensing arithmetic, in one place.
 *
 * Three counts, and the whole model is the relationship between them:
 *
 * ```
 * licenseCount     licenses the organization holds. Its own number, on `organizations`
 * membersOccupying members in it - every member occupies exactly one license
 * vacant           licenseCount - membersOccupying
 * ```
 *
 * **A license is not a member, and a member is not a license.** Removing a member does not
 * remove a license; it vacates one, and the organization keeps paying for it until an
 * administrator explicitly gives it up. That asymmetry is the point of the model - a team that
 * loses somebody on Friday can put their replacement in the same license on Monday without
 * buying anything.
 *
 * ### What Stripe is billed for
 *
 * ```
 * paidLicenses = max(licenseCount - 1, 0)      the first license is included, free
 * ```
 *
 * The Stripe subscription's quantity is [paidLicensesFor], **not** [licenseCount]. One
 * license is free, so an organization holding five licenses is billed for four, at a flat
 * per-unit price. Keeping the free one out of the quantity is what lets the Stripe price stay
 * an ordinary per-unit price instead of a graduated tier whose first unit costs nothing.
 *
 * | licenses | members | vacant | Stripe quantity | monthly |
 * | -------- | ------- | ------ | --------------- | ------- |
 * | 1        | 1       | 0      | 0 (no sub)      | free    |
 * | 2        | 2       | 0      | 1               | 1 × $X  |
 * | 5        | 5       | 0      | 4               | 4 × $X  |
 * | 5        | 4       | 1      | 4               | 4 × $X  |
 * | 5        | 3       | 2      | 4               | 4 × $X  |
 *
 * ### Outstanding invitations hold a license
 *
 * A license is acquired when an invitation is *issued*, not when it is accepted - so an
 * un-accepted invitation is holding one. [licensesAvailableFor] is therefore the number that
 * decides whether another invitation can go out, and it subtracts both. Counting only members
 * would let two invitations issued back to back both see the same single vacant license, and
 * the organization would end up with more people in it than it holds licenses for.
 *
 * Not a bean, and deliberately not configurable. It is one piece of arithmetic that several
 * services must agree on, and the only thing worse than the wrong formula would be two of them.
 */
object OrganizationLicenses {

    /** Every organization holds at least this many licenses: the free, included one. */
    const val INCLUDED_LICENSES = 1L

    /** Licenses Stripe is billed for. The first is included, so it is never charged. */
    fun paidLicensesFor(licenseCount: Long): Long =
        (licenseCount - INCLUDED_LICENSES).coerceAtLeast(0)

    /**
     * Licenses with no member in them.
     *
     * This is [OrganizationLicenses]' answer to "vacant" as the product defines it - purchased
     * but unassigned - and it deliberately ignores outstanding invitations, because a person
     * who has not accepted yet is not a member. Use [licensesAvailableFor] to decide whether
     * another person can be brought in.
     */
    fun vacantLicensesFor(licenseCount: Long, membersOccupying: Long): Long =
        licenseCount - membersOccupying

    /**
     * Licenses free to allocate right now: vacant, minus the ones outstanding invitations are
     * already holding.
     *
     * Zero or less means the next person needs a license acquired for them.
     */
    fun licensesAvailableFor(licenseCount: Long, membersOccupying: Long, pendingInvitations: Long): Long =
        licenseCount - membersOccupying - pendingInvitations

    /**
     * Whether one more person can be brought in without acquiring a license.
     *
     * Reads as "there is a license free for them", and is the check both invitation issuing
     * and invitation acceptance make - the first to decide whether to buy one, the second to
     * confirm the one bought for them is still there.
     */
    fun hasLicenseAvailable(licenseCount: Long, membersOccupying: Long, pendingInvitations: Long): Boolean =
        licensesAvailableFor(licenseCount, membersOccupying, pendingInvitations) > 0

    /**
     * The fewest licenses an organization of this size can hold.
     *
     * The floor on reducing a license count: an administrator may shrink to exactly what their
     * members occupy and no further, because the alternative is the application choosing which
     * colleague to turn out. Never below [INCLUDED_LICENSES] either - the free license always
     * exists, and an organization always has at least its owner in it.
     *
     * Outstanding invitations are deliberately **not** part of the floor. Blocking a reduction
     * for up to a week because somebody was invited and never answered would be a worse
     * failure than the one it prevents; an invitation whose license is gone by the time it is
     * accepted is simply refused, and the invitation survives.
     */
    fun minimumLicensesFor(membersOccupying: Long): Long =
        maxOf(membersOccupying, INCLUDED_LICENSES)
}
