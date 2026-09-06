package com.tictac.io.project

import com.tictac.io.organization.OrganizationAccess
import com.tictac.io.organization.OrganizationContext
import com.tictac.io.organization.OrganizationRole
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Not found, not in this organization, or not visible to this caller - indistinguishable.
 *
 * Same reasoning as [com.tictac.io.organization.OrganizationNotFoundException]. A project a
 * caller cannot see must not be provable to exist: an organization MEMBER who is not
 * assigned has no legitimate way to learn that "Acme Q4 Layoffs" is a project, and a
 * 403-versus-404 split would tell them.
 */
class ProjectNotFoundException : RuntimeException("Project not found")

/**
 * Whether this organization role sees, and administers, every project in the organization.
 *
 * Visibility and management coincide today, which is why one predicate answers both. They
 * would split the moment someone wants a "can see all projects but not edit them" role -
 * at which point this becomes two functions, and the call sites already say which they
 * meant. Kept in the project package: whether a role administers *projects* is a project
 * decision, and [OrganizationRole] should not accumulate a clause per downstream feature.
 */
internal fun OrganizationRole.administersProjects(): Boolean =
    this == OrganizationRole.OWNER || this == OrganizationRole.ADMIN

/**
 * What the caller may do with one project.
 *
 * [assignment] is null for an OWNER or ADMIN who is not personally on the project - they can
 * still see and manage it. That distinction is what time tracking will need later: being
 * able to administer a project is not the same as being on it.
 */
data class ProjectContext(
    val organization: OrganizationContext,
    val project: Project,
    val assignment: ProjectMember?,
) {
    val projectId: UUID get() = project.id!!
    val userId: UUID get() = organization.userId
    val organizationId: UUID get() = organization.organizationId
    val organizationRole: OrganizationRole get() = organization.role
    val isAssigned: Boolean get() = assignment != null
}

/**
 * The project-scoped authorisation gate, layered on top of [OrganizationAccess].
 *
 * The full chain, in order, every time:
 *
 * ```
 * access token → organization membership → organization role → project in that organization
 *              → project assignment → permission
 * ```
 *
 * The first two steps are delegated rather than reimplemented - [OrganizationAccess] already
 * answers "is this caller a tenant of this organization?", and duplicating that check here
 * would be a second place for it to go wrong. What this adds is the two project-specific
 * links: that the project belongs to *the organization in the URL*, and that the caller is
 * entitled to it.
 *
 * Both project ids and organization ids arrive from the URL and are untrusted. Neither is
 * ever used alone: the project is looked up by (project id, organization id) together, so a
 * project id borrowed from another tenant resolves to nothing no matter how valid it is.
 *
 * Every project-scoped service method starts here. Nothing else queries [ProjectRepository]
 * by project id.
 */
@Service
class ProjectAccess(
    private val organizationAccess: OrganizationAccess,
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
) {

    /**
     * Resolves [projectId] within [organizationId] for the current caller, or refuses.
     *
     * With no [allowedRoles], any caller who can *see* the project passes - an administrator
     * of the organization, or a member assigned to it. Otherwise the caller's organization
     * role must additionally be one of them.
     *
     * @throws com.tictac.io.organization.OrganizationNotFoundException if the caller is not
     *   a member of the organization, or it does not exist or is soft-deleted.
     * @throws ProjectNotFoundException if the project does not exist, belongs to a different
     *   organization, or is invisible to this caller. Deliberately one answer for all three.
     * @throws AccessDeniedException if the caller can see the project but holds none of
     *   [allowedRoles]. Safe to distinguish: they can already see it, so a 403 tells them
     *   nothing new.
     */
    @Transactional(readOnly = true)
    fun require(
        organizationId: UUID,
        projectId: UUID,
        vararg allowedRoles: OrganizationRole,
    ): ProjectContext {
        val organization = organizationAccess.require(organizationId)

        val project = projectRepository.findByIdAndOrganizationId(projectId, organizationId)
            ?: throw ProjectNotFoundException()

        val assignment = projectMemberRepository.findByProjectIdAndUserId(projectId, organization.userId)

        // Visibility before role. An unassigned MEMBER must get "no such project" rather
        // than "forbidden" - otherwise the refusal itself confirms the project exists.
        if (!organization.role.administersProjects() && assignment == null) {
            throw ProjectNotFoundException()
        }

        if (allowedRoles.isNotEmpty() && organization.role !in allowedRoles) {
            throw AccessDeniedException(
                "Role ${organization.role} may not perform this operation on project $projectId",
            )
        }

        return ProjectContext(organization, project, assignment)
    }

    /**
     * Organization-level authorisation only, for the operations that are not about one
     * existing project: creating one, and listing them. Exists so those two do not have to
     * reach past this class to [OrganizationAccess] and re-derive the same context.
     */
    @Transactional(readOnly = true)
    fun requireOrganization(organizationId: UUID, vararg allowedRoles: OrganizationRole): OrganizationContext =
        organizationAccess.require(organizationId, *allowedRoles)
}
