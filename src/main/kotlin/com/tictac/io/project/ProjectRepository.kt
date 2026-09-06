package com.tictac.io.project

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ProjectRepository : JpaRepository<Project, UUID> {

    /**
     * The tenancy lookup, and the reason cross-tenant project access fails.
     *
     * Always both ids, never `findById` alone. A project id from another organization is a
     * perfectly valid UUID that exists in this table; what makes it unreachable is that it
     * does not match the organization the caller was authorised for. Resolving a project by
     * id and *then* comparing its organization would work too, but only for as long as
     * nobody forgets the second step - here there is no second step to forget.
     */
    fun findByIdAndOrganizationId(id: UUID, organizationId: UUID): Project?

    /** Every project in the organization. For roles that administer projects. */
    fun findAllByOrganizationIdOrderByCreatedAtAsc(organizationId: UUID): List<Project>

    /**
     * The projects one user is actually assigned to, within one organization.
     *
     * This is a MEMBER's whole view of the organization's work, and it is the query time
     * tracking will build on: "which projects may I record against?" is this list, filtered
     * to the active ones.
     */
    @Query(
        """
        SELECT p FROM Project p, ProjectMember pm
        WHERE pm.projectId = p.id
          AND pm.userId = :userId
          AND p.organizationId = :organizationId
        ORDER BY p.createdAt ASC
        """,
    )
    fun findAssignedInOrganization(
        @Param("organizationId") organizationId: UUID,
        @Param("userId") userId: UUID,
    ): List<Project>
}
