package com.tictac.io.project

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ProjectMemberRepository : JpaRepository<ProjectMember, UUID> {

    /**
     * The project-level authorisation lookup, used by [ProjectAccess]. A null result means
     * the caller is not assigned - which for an organization MEMBER means the project is
     * not visible to them at all.
     */
    fun findByProjectIdAndUserId(projectId: UUID, userId: UUID): ProjectMember?

    fun countByProjectId(projectId: UUID): Long

    /**
     * A project's members, joined to the user rows for the display fields. Four safe
     * columns only - there is no query path here that could return a password hash.
     *
     * Closed accounts are filtered out, matching
     * [com.tictac.io.organization.OrganizationMemberRepository.findMembersOfOrganization]:
     * a soft-deleted user cannot authenticate, so listing them would publish the email
     * address of an account that no longer exists.
     *
     * Callers must already have been authorised for the project - this does not check, which
     * is why it is never reached from a controller.
     */
    @Query(
        """
        SELECT new com.tictac.io.project.ProjectMemberResponse(
            u.id, u.firstName, u.lastName, u.email, pm.createdAt
        )
        FROM ProjectMember pm, com.tictac.io.user.User u
        WHERE u.id = pm.userId
          AND pm.projectId = :projectId
          AND u.deletedAt IS NULL
        ORDER BY pm.createdAt ASC
        """,
    )
    fun findMembersOfProject(@Param("projectId") projectId: UUID): List<ProjectMemberResponse>

    /**
     * Deletes every assignment one user holds across one organization's projects.
     *
     * This is what keeps "a project member is also an organization member" true when
     * someone is removed from an organization. It cannot be a foreign key - this table
     * reaches the organization only through `projects.organization_id`, one hop away - so
     * it is a single statement run inside the removal's own transaction.
     *
     * Scoped to the organization on purpose. The same person may be assigned to projects in
     * a different organization they still belong to, and those must not be touched.
     */
    @Modifying
    @Query(
        """
        DELETE FROM ProjectMember pm
        WHERE pm.userId = :userId
          AND pm.projectId IN (SELECT p.id FROM Project p WHERE p.organizationId = :organizationId)
        """,
    )
    fun deleteAllForUserInOrganization(
        @Param("organizationId") organizationId: UUID,
        @Param("userId") userId: UUID,
    ): Int
}
