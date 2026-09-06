package com.tictac.io.organization

import com.tictac.io.user.UserRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Membership management within one organization.
 *
 * There is no "add member" here on purpose. Membership is created two ways: by founding an
 * organization (see [OrganizationService.create]), and - once it exists - by accepting an
 * invitation. Inventing a direct "add this email to my organization" endpoint now would be
 * a way to attach a stranger's account to your tenant without their consent, and it would
 * have to be removed again when invitations land. The unique index on
 * `(organization_id, user_id)` means whatever creates the row cannot create a duplicate.
 */
@Service
class OrganizationMembershipService(
    private val organizationAccess: OrganizationAccess,
    private val organizationMemberRepository: OrganizationMemberRepository,
    private val userRepository: UserRepository,
) {

    /** Any member may see who else is in the organization. */
    @Transactional(readOnly = true)
    fun listMembers(organizationId: UUID): List<OrganizationMemberResponse> {
        val context = organizationAccess.require(organizationId)

        return organizationMemberRepository.findMembersOfOrganization(context.organizationId)
    }

    /**
     * Changes one member's role.
     *
     * Two checks beyond "is the caller an administrator", both on [OrganizationRole]:
     *
     * - the caller must outrank the *current* holder, so an ADMIN cannot demote a peer and
     *   nobody can touch the OWNER (which is also what keeps the organization from being
     *   left without one, and what stops the owner demoting themselves by accident);
     * - the caller must be allowed to hand out the *new* role, which rules out granting
     *   OWNER entirely and rules out granting anything above the caller's own standing.
     *
     * Together those make privilege escalation - by a member, an admin, or an admin acting
     * on themselves - fail closed. Promoting someone to owner is a transfer of the
     * organization and needs its own operation; it is not implemented yet.
     */
    @Transactional
    fun changeRole(
        organizationId: UUID,
        targetUserId: UUID,
        request: ChangeMemberRoleRequest,
    ): OrganizationMemberResponse {
        val context = organizationAccess.require(organizationId, OrganizationRole.OWNER, OrganizationRole.ADMIN)

        // Safe: validated as @NotNull by the controller.
        val newRole = request.role!!
        val target = requireMember(context.organizationId, targetUserId)

        if (!context.role.outranks(target.role)) {
            throw AccessDeniedException(
                "Role ${context.role} may not modify a member holding ${target.role}",
            )
        }
        if (!context.role.canAssign(newRole)) {
            throw AccessDeniedException("Role ${context.role} may not assign $newRole")
        }

        target.role = newRole

        val user = userRepository.findById(targetUserId).orElseThrow { OrganizationMemberNotFoundException() }

        return OrganizationMemberResponse(
            userId = user.id!!,
            firstName = user.firstName,
            lastName = user.lastName,
            email = user.email,
            role = target.role,
            joinedAt = target.createdAt,
        )
    }

    /**
     * Removes a member from the organization.
     *
     * Same rank rule as [changeRole], which is what protects the owner: no role outranks
     * OWNER, so the owner cannot be removed by an admin and cannot remove themselves. The
     * organization therefore always has exactly one owner for as long as it exists.
     *
     * Only the membership row goes. The user account is untouched - they may well be a
     * member of other organizations - and so is anything they created here.
     *
     * A member leaving of their own accord is a different operation with different rules
     * (the owner still could not use it) and is not implemented.
     */
    @Transactional
    fun removeMember(organizationId: UUID, targetUserId: UUID) {
        val context = organizationAccess.require(organizationId, OrganizationRole.OWNER, OrganizationRole.ADMIN)
        val target = requireMember(context.organizationId, targetUserId)

        if (!context.role.outranks(target.role)) {
            throw AccessDeniedException(
                "Role ${context.role} may not remove a member holding ${target.role}",
            )
        }

        organizationMemberRepository.delete(target)
    }

    /**
     * The caller has already been authorised for this organization, so a 404 here reveals
     * only that someone they could have seen in the member list is not in it.
     */
    private fun requireMember(organizationId: UUID, userId: UUID): OrganizationMember =
        organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)
            ?: throw OrganizationMemberNotFoundException()
}
