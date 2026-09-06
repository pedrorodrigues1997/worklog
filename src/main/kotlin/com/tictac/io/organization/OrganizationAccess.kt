package com.tictac.io.organization

import com.tictac.io.user.ActiveUser
import com.tictac.io.user.User
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Not found, or not ours - the caller cannot tell which.
 *
 * Organization ids are opaque UUIDs, but they travel: they sit in URLs, get pasted into
 * tickets, and appear in the responses of anyone who *is* a member. Answering 403 for an
 * organization that exists and 404 for one that does not turns any leaked id into an
 * existence oracle, and a tenant's existence is itself customer information. So a caller
 * with no membership gets the same answer whether the organization is real, deleted, or
 * imaginary. Same reasoning as the deliberately identical login failures.
 */
class OrganizationNotFoundException : RuntimeException("Organization not found")

/** The organization exists and the caller is a member of it; the *target* member is not. */
class OrganizationMemberNotFoundException : RuntimeException("Member not found in this organization")

/**
 * What the caller is allowed to do in one organization, resolved once per request.
 *
 * Holding the [membership] rather than just the role means callers that need to compare
 * themselves to another member (see [OrganizationMembershipService]) do not have to look
 * their own row up again.
 */
data class OrganizationContext(
    val user: User,
    val organization: Organization,
    val membership: OrganizationMember,
) {
    val organizationId: UUID get() = organization.id!!
    val userId: UUID get() = membership.userId
    val role: OrganizationRole get() = membership.role
}

/**
 * The single gate for organization-scoped authorization.
 *
 * Authentication has already answered "who is this?" by the time a request reaches a
 * controller. This answers the separate question "may this person operate on *this*
 * organization?", and it must be asked every time, because an authenticated user is a
 * stranger to every organization but their own.
 *
 * The organization id is taken from the request - it has to be, the frontend chooses which
 * tenant it is working in - and is therefore untrusted. What makes it safe is that it is
 * only ever used as one half of a membership lookup whose other half comes from the access
 * token. An id the caller has no row for resolves to nothing, so tampering with it widens
 * access to exactly nowhere.
 *
 * Every organization-scoped service method starts with a call to [require]. Nothing else
 * should query [OrganizationRepository] or [OrganizationMemberRepository] by organization
 * id: an endpoint that forgets this gate is a cross-tenant data leak, and the way to keep
 * that from happening is to have one place that cannot be forgotten rather than a check
 * copied into every controller.
 */
@Service
class OrganizationAccess(
    private val activeUser: ActiveUser,
    private val organizationRepository: OrganizationRepository,
    private val organizationMemberRepository: OrganizationMemberRepository,
) {

    /**
     * Resolves [organizationId] for the current caller, or refuses the request.
     *
     * With no [allowedRoles], any member passes - use that for reads every member may
     * perform. Otherwise the caller's role must be one of them.
     *
     * @throws OrganizationNotFoundException if the organization does not exist, has been
     *   soft-deleted, or the caller is not a member of it. Deliberately the same failure
     *   for all three.
     * @throws AccessDeniedException if the caller is a member but holds none of
     *   [allowedRoles]. Safe to distinguish here: a member already knows the organization
     *   exists, so a 403 tells them nothing they could not see in their own listing.
     */
    @Transactional(readOnly = true)
    fun require(organizationId: UUID, vararg allowedRoles: OrganizationRole): OrganizationContext {
        // First, that the caller's own account still exists - an access token outlives a
        // deleted account for up to its 15-minute lifetime.
        val user = activeUser.require()

        val organization = organizationRepository.findByIdAndDeletedAtIsNull(organizationId)
            ?: throw OrganizationNotFoundException()

        val membership = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, user.id!!)
            ?: throw OrganizationNotFoundException()

        if (allowedRoles.isNotEmpty() && membership.role !in allowedRoles) {
            throw AccessDeniedException(
                "Role ${membership.role} may not perform this operation on organization $organizationId",
            )
        }

        return OrganizationContext(user, organization, membership)
    }
}
