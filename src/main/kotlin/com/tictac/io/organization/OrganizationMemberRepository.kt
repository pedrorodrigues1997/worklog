package com.tictac.io.organization

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface OrganizationMemberRepository : JpaRepository<OrganizationMember, UUID> {

    /**
     * The tenant-isolation lookup. Everything organization-scoped goes through this, via
     * [OrganizationAccess] - a null result means the caller is a stranger to that
     * organization, whatever id they supplied.
     */
    fun findByOrganizationIdAndUserId(organizationId: UUID, userId: UUID): OrganizationMember?

    fun countByOrganizationIdAndRole(organizationId: UUID, role: OrganizationRole): Long

    /**
     * The canonical member count, and the basis of every billing decision.
     *
     * Every row, whatever the role - the owner is a person and is counted like anyone else.
     * There is deliberately no second count stored anywhere: a cached `member_count` column
     * would be one more thing to drift from the memberships it claims to describe, and this
     * table is the membership model.
     *
     * Note that this counts rows, so a member whose *account* has since been closed still
     * occupies a seat. That is intentional - the seat is allocated until an administrator
     * removes the membership - but it does mean the count can exceed what
     * [findMembersOfOrganization] lists. See the note in OrganizationLicenseService.
     */
    fun countByOrganizationId(organizationId: UUID): Long

    /**
     * The organization's owner, with the row locked for update (`SELECT ... FOR UPDATE`).
     *
     * This is the serialisation point for ownership transfer. Two concurrent transfers of
     * the same organization both reach this query; one takes the lock, the other waits.
     * When the loser resumes, PostgreSQL re-evaluates the predicate against the row it was
     * waiting on - which the winner has just demoted to ADMIN - so it no longer matches
     * `role = OWNER` and the query returns nothing. The loser therefore finds no owner to
     * demote and fails cleanly, rather than racing the winner to a second OWNER row and
     * getting a raw constraint violation.
     *
     * [role] is a parameter only because a JPQL literal for an enum constant is awkward;
     * callers pass [OrganizationRole.OWNER].
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM OrganizationMember m WHERE m.organizationId = :organizationId AND m.role = :role")
    fun findAndLockOwner(
        @Param("organizationId") organizationId: UUID,
        @Param("role") role: OrganizationRole,
    ): OrganizationMember?

    /**
     * The caller's organizations, with the role they hold in each.
     *
     * A theta join rather than an association walk, because [OrganizationMember] holds
     * plain ids by design. Soft-deleted organizations are filtered out here: a listing is
     * the one place a deleted tenant would otherwise still be visible.
     *
     * The projection is built straight into the response DTO. Copying it through an
     * identical intermediate type would only add a mapping step with nothing to decide,
     * and Hibernate validates the constructor expression at startup, so a DTO change that
     * breaks this query fails the build rather than a request.
     */
    @Query(
        """
        SELECT new com.tictac.io.organization.OrganizationResponse(o.id, o.name, m.role, o.createdAt)
        FROM OrganizationMember m, Organization o
        WHERE o.id = m.organizationId
          AND m.userId = :userId
          AND o.deletedAt IS NULL
        ORDER BY o.createdAt ASC
        """,
    )
    fun findOrganizationsForUser(@Param("userId") userId: UUID): List<OrganizationResponse>

    /**
     * Members of one organization, joined to the user rows for the display fields. Only
     * the four safe columns are selected; there is no query path here that could return a
     * password hash even by accident.
     *
     * Closed accounts are excluded. A soft-deleted user cannot authenticate, so listing
     * them as members would publish the email address of an account that no longer exists.
     *
     * Callers must already have been authorised for [organizationId] - this method does
     * not check membership, which is exactly why it is never called from a controller.
     */
    @Query(
        """
        SELECT new com.tictac.io.organization.OrganizationMemberResponse(
            u.id, u.firstName, u.lastName, u.email, m.role, m.createdAt
        )
        FROM OrganizationMember m, com.tictac.io.user.User u
        WHERE u.id = m.userId
          AND m.organizationId = :organizationId
          AND u.deletedAt IS NULL
        ORDER BY m.createdAt ASC
        """,
    )
    fun findMembersOfOrganization(@Param("organizationId") organizationId: UUID): List<OrganizationMemberResponse>
}
