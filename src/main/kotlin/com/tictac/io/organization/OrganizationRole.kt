package com.tictac.io.organization

/**
 * A member's standing within one organization. Persisted by name, never by ordinal, so
 * the declaration order here is free to change.
 *
 * The two predicates below are the whole membership-management policy, kept on the role
 * itself so no controller or service has to re-derive it from string comparisons. They
 * are intentionally narrow: this is not a permission system, and it should not grow into
 * one until there are resources other than membership to protect.
 *
 * [rank] is explicit rather than [ordinal] because it is load-bearing - a reordering of
 * the constants would otherwise silently rewrite the policy.
 */
enum class OrganizationRole(private val rank: Int) {

    /**
     * Created the organization, or had it transferred to them. Full control, including
     * everything billing and ownership related once that exists. There is exactly one
     * per organization, enforced by a partial unique index in V6.
     */
    OWNER(0),

    /** Day-to-day administration: organization settings and ordinary members. */
    ADMIN(1),

    /** Belongs to the organization and uses its resources. Manages nobody. */
    MEMBER(2),

    ;

    /**
     * Whether a caller in this role may act on a member holding [other].
     *
     * Strictly greater standing is required, which yields three properties worth stating:
     * nobody can act on the OWNER (so an organization cannot be left ownerless, and the
     * owner cannot remove themselves by accident), an ADMIN cannot demote or remove a
     * fellow ADMIN, and no role can act on itself.
     */
    fun outranks(other: OrganizationRole): Boolean = rank < other.rank

    /**
     * Whether a caller in this role may hand out [role].
     *
     * OWNER is never assignable through the membership API. Promoting someone to owner is
     * a transfer of the organization - it has to demote the current owner in the same
     * step - so it belongs in its own operation rather than falling out of a role edit.
     * Otherwise a caller may assign their own level or below, which lets an ADMIN promote
     * a MEMBER to ADMIN but never lets anyone exceed the authority they already have.
     */
    fun canAssign(role: OrganizationRole): Boolean = role != OWNER && rank <= role.rank
}
