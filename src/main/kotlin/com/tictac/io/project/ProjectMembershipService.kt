package com.tictac.io.project

import com.tictac.io.organization.OrganizationMemberNotFoundException
import com.tictac.io.organization.OrganizationMemberRepository
import com.tictac.io.organization.OrganizationRole
import com.tictac.io.user.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** The person is not assigned to this project. */
class ProjectMemberNotFoundException : RuntimeException("User is not a member of this project")

/**
 * The assignment is well-formed but cannot be applied - already assigned, or the account
 * has been closed.
 */
class ProjectAssignmentConflictException(message: String) : RuntimeException(message)

/**
 * Assigning organization members to projects, and removing them again.
 *
 * The domain rule this service exists to hold up is that **a project member is always an
 * organization member**. It cannot be a foreign key - `project_members` reaches the
 * organization only through `projects.organization_id`, one hop away - so it is enforced
 * from both directions instead: nothing is assigned here without an organization membership
 * being checked first, and
 * [com.tictac.io.organization.OrganizationMembershipService.removeMember] deletes a
 * departing member's assignments in the same transaction that removes them.
 *
 * Belonging to the organization deliberately grants nothing at project level. Assignment is
 * always an explicit act by an administrator, which is what lets a company have projects
 * that not everybody is on.
 */
@Service
class ProjectMembershipService(
    private val projectAccess: ProjectAccess,
    private val projectMemberRepository: ProjectMemberRepository,
    private val organizationMemberRepository: OrganizationMemberRepository,
    private val userRepository: UserRepository,
) {

    /**
     * Anyone who can see the project can see who is on it - administrators of the
     * organization, and the people assigned to it. Colleagues on a shared piece of work
     * need to know who their colleagues are.
     */
    @Transactional(readOnly = true)
    fun listMembers(organizationId: UUID, projectId: UUID): List<ProjectMemberResponse> {
        val context = projectAccess.require(organizationId, projectId)

        return projectMemberRepository.findMembersOfProject(context.projectId)
    }

    /**
     * Assigns an organization member to a project. OWNER and ADMIN only.
     *
     * The tenancy check that matters is the organization-membership lookup below. It is
     * scoped to the organization the *project* belongs to, which the caller has already been
     * authorised for - so a user id belonging to some other company resolves to nothing, and
     * there is no ordering of valid-looking ids that produces a cross-tenant assignment.
     */
    @Transactional
    fun addMember(
        organizationId: UUID,
        projectId: UUID,
        request: AddProjectMemberRequest,
    ): ProjectMemberResponse {
        val context = projectAccess.require(
            organizationId,
            projectId,
            OrganizationRole.OWNER,
            OrganizationRole.ADMIN,
        )

        // Safe: validated as @NotNull by the controller.
        val targetUserId = request.userId!!

        // Not a member of this organization - or not a real account at all. One answer for
        // both, so this cannot be used to probe for user ids.
        organizationMemberRepository.findByOrganizationIdAndUserId(context.organizationId, targetUserId)
            ?: throw OrganizationMemberNotFoundException()

        val targetUser = userRepository.findById(targetUserId).orElseThrow { OrganizationMemberNotFoundException() }

        if (targetUser.deletedAt != null) {
            // A closed account keeps its organization membership row, so it is reachable
            // here; it must not be assignable. It could never sign in to do the work, and
            // it is filtered out of every member listing, so the assignment would be
            // invisible as well as useless.
            throw ProjectAssignmentConflictException("Cannot assign a closed account to a project")
        }

        if (projectMemberRepository.findByProjectIdAndUserId(context.projectId, targetUserId) != null) {
            throw ProjectAssignmentConflictException("User is already a member of this project")
        }

        val assignment = try {
            // saveAndFlush, not save: the check above loses to a concurrent assignment of
            // the same person, and the unique index is the actual guarantee. Flushing here
            // makes that violation catchable in this method rather than at commit.
            projectMemberRepository.saveAndFlush(ProjectMember(projectId = context.projectId, userId = targetUserId))
        } catch (ex: DataIntegrityViolationException) {
            throw ProjectAssignmentConflictException("User is already a member of this project")
        }

        return ProjectMemberResponse(
            userId = targetUser.id!!,
            firstName = targetUser.firstName,
            lastName = targetUser.lastName,
            email = targetUser.email,
            assignedAt = assignment.createdAt,
        )
    }

    /**
     * Removes an assignment. OWNER and ADMIN only.
     *
     * **Self-removal is not offered**, and that is a decision rather than an omission. The
     * brief allowed it "if it does not conflict with the existing authorization model", and
     * it does: nowhere in this codebase can anyone leave anything on their own. An
     * organization MEMBER cannot remove themselves from an organization, and the owner
     * cannot either. Adding a second authorisation rule to this one endpoint would make
     * project assignment the sole exception, for no product need - and it would let someone
     * quietly drop off a project they had been assigned to, which an administrator would
     * only discover by noticing the missing work.
     *
     * "Leave" is worth building properly, once, at both levels. It is on the open list.
     *
     * An administrator removing *their own* assignment is not a special case and is allowed:
     * they are exercising the same power over themselves that they hold over everyone else,
     * and they keep full access to the project either way, since administrators are not
     * gated on assignment.
     */
    @Transactional
    fun removeMember(organizationId: UUID, projectId: UUID, targetUserId: UUID) {
        val context = projectAccess.require(
            organizationId,
            projectId,
            OrganizationRole.OWNER,
            OrganizationRole.ADMIN,
        )

        val assignment = projectMemberRepository.findByProjectIdAndUserId(context.projectId, targetUserId)
            ?: throw ProjectMemberNotFoundException()

        projectMemberRepository.delete(assignment)
    }
}
