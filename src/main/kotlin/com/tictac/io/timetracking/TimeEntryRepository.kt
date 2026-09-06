package com.tictac.io.timetracking

import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.Instant
import java.util.UUID

interface TimeEntryRepository : JpaRepository<TimeEntry, UUID>, JpaSpecificationExecutor<TimeEntry> {

    /**
     * The tenancy lookup, and the reason cross-tenant access to an entry fails.
     *
     * Always both ids, never `findById` alone - the same rule as
     * [com.tictac.io.project.ProjectRepository.findByIdAndOrganizationId]. An entry id from
     * another organization is a valid UUID that really exists; requiring both ids to match
     * at once is what makes it unreachable, with no second step anyone can forget.
     */
    fun findByIdAndOrganizationIdAndDeletedAtIsNull(id: UUID, organizationId: UUID): TimeEntry?

    /**
     * The caller's running timer in one organization, if any.
     *
     * At most one row can match: the partial unique index in V9 covers exactly this
     * predicate, so this returns a single result by construction rather than by hope.
     */
    fun findByOrganizationIdAndUserIdAndEndedAtIsNullAndDeletedAtIsNull(
        organizationId: UUID,
        userId: UUID,
    ): TimeEntry?

    fun countByProjectIdAndDeletedAtIsNull(projectId: UUID): Long
}

/**
 * The filters behind `GET /time-entries`, as a composable [Specification].
 *
 * Specifications rather than a JPQL query with `(:param IS NULL OR ...)` for every filter:
 * five optional parameters give thirty-two query shapes, and binding a null parameter of an
 * inferred type is exactly where Hibernate's type resolution gets fragile. Here an absent
 * filter contributes no predicate at all, so the generated SQL says only what was asked.
 *
 * [scopedUserId] is not a filter in the same sense as the rest - it is the authorisation
 * scope, applied for callers who may only see their own entries. It is ANDed with the
 * caller's own `userId` filter rather than replacing it, so a member asking for someone
 * else's entries gets an empty page instead of a leak or a special-cased error.
 */
object TimeEntryFilters {

    fun of(
        organizationId: UUID,
        scopedUserId: UUID?,
        projectId: UUID? = null,
        projectCategoryId: UUID? = null,
        userId: UUID? = null,
        from: Instant? = null,
        to: Instant? = null,
        billable: Boolean? = null,
    ): Specification<TimeEntry> =
        Specification { root, _, builder ->
            val predicates = mutableListOf<Predicate>()

            // Tenancy and soft delete first: these are never optional.
            predicates += builder.equal(root.get<UUID>("organizationId"), organizationId)
            predicates += builder.isNull(root.get<Instant>("deletedAt"))

            scopedUserId?.let { predicates += builder.equal(root.get<UUID>("userId"), it) }
            userId?.let { predicates += builder.equal(root.get<UUID>("userId"), it) }
            projectId?.let { predicates += builder.equal(root.get<UUID>("projectId"), it) }

            // Safe to apply on its own: the organization predicate above is unconditional, so
            // a category id from another tenant matches nothing rather than reaching across.
            projectCategoryId?.let { predicates += builder.equal(root.get<UUID>("projectCategoryId"), it) }
            billable?.let { predicates += builder.equal(root.get<Boolean>("billable"), it) }

            // Ranged on started_at, which is the column every index trails on. Inclusive
            // from, exclusive to, so consecutive periods tile without double-counting a
            // boundary entry - the property reporting will need.
            from?.let { predicates += builder.greaterThanOrEqualTo(root.get("startedAt"), it) }
            to?.let { predicates += builder.lessThan(root.get("startedAt"), it) }

            builder.and(*predicates.toTypedArray())
        }
}
