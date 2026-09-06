package com.tictac.io.project

import com.tictac.io.organization.OrganizationRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProjectService(
    private val projectAccess: ProjectAccess,
    private val projectRepository: ProjectRepository,
) {

    /**
     * Creates a project inside the organization from the URL. OWNER and ADMIN only.
     *
     * The organization is taken from the path, after that path has been authorised - there
     * is no field in the request that could point the project somewhere else, so a project
     * cannot be created in an organization the caller has no standing in.
     */
    @Transactional
    fun create(organizationId: UUID, request: CreateProjectRequest): ProjectResponse {
        val context = projectAccess.requireOrganization(
            organizationId,
            OrganizationRole.OWNER,
            OrganizationRole.ADMIN,
        )

        // Safe: the controller validates with @Valid, so the name is present and non-blank.
        val project = Project(
            organizationId = context.organizationId,
            name = request.name!!.trim(),
            description = request.description?.trim()?.takeIf { it.isNotEmpty() },
        )

        return projectRepository.save(project).toResponse()
    }

    /**
     * The organization's projects, as this caller is entitled to see them.
     *
     * Two different lists behind one endpoint, and deliberately so. An administrator gets
     * the organization's projects; anyone else gets only the ones they are assigned to,
     * because organization membership grants nothing at project level on its own. That is
     * what makes this endpoint usable directly as the frontend's project selector.
     *
     * [active] filters on the archive flag - `true` for the selectable ones, `false` for the
     * archive, absent for both. The filter is applied in memory rather than in SQL because
     * an organization has tens of projects, not thousands, and the alternative is a second
     * pair of queries. Move it into the query if that assumption ever stops holding.
     */
    @Transactional(readOnly = true)
    fun list(organizationId: UUID, active: Boolean? = null): List<ProjectResponse> {
        val context = projectAccess.requireOrganization(organizationId)

        val projects = if (context.role.administersProjects()) {
            projectRepository.findAllByOrganizationIdOrderByCreatedAtAsc(organizationId)
        } else {
            projectRepository.findAssignedInOrganization(organizationId, context.userId)
        }

        return projects.filter { active == null || it.isActive == active }.map { it.toResponse() }
    }

    /** Visible to administrators of the organization, and to members assigned to it. */
    @Transactional(readOnly = true)
    fun get(organizationId: UUID, projectId: UUID): ProjectResponse =
        projectAccess.require(organizationId, projectId).project.toResponse()

    /**
     * Updates name, description and the archive flag. OWNER and ADMIN only.
     *
     * Archiving is an ordinary update rather than its own endpoint: `{"isActive": false}`
     * says exactly what a `POST /archive` would, and a second route would need its own
     * authorisation, its own tests and its own inverse. Projects are never deleted - time
     * entries will point at them, and an archived project still has to render every
     * historical entry that references it.
     */
    @Transactional
    fun update(organizationId: UUID, projectId: UUID, request: UpdateProjectRequest): ProjectResponse {
        val context = projectAccess.require(
            organizationId,
            projectId,
            OrganizationRole.OWNER,
            OrganizationRole.ADMIN,
        )

        val project = context.project

        // Absent means "leave it". An empty description is how a caller clears one, which
        // is why the blank check is separate from the null check.
        request.name?.trim()?.takeIf { it.isNotEmpty() }?.let { project.name = it }
        request.description?.let { project.description = it.trim().takeIf { trimmed -> trimmed.isNotEmpty() } }
        request.isActive?.let { project.isActive = it }

        // Managed entity inside this transaction: the update flushes at commit, and
        // Project.onUpdate stamps updated_at as it goes.
        return project.toResponse()
    }
}

internal fun Project.toResponse() =
    ProjectResponse(
        id = id!!,
        name = name,
        description = description,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
