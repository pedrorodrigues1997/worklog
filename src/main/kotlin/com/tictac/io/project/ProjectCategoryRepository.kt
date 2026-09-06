package com.tictac.io.project

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ProjectCategoryRepository : JpaRepository<ProjectCategory, UUID> {

    /**
     * The consistency lookup, and the reason a category from another project is unreachable.
     *
     * Always both ids, never `findById` alone - the same rule as
     * [ProjectRepository.findByIdAndOrganizationId] and its time-entry equivalent. A category
     * id borrowed from a sibling project is a valid UUID that really exists; requiring the
     * pair to match is what makes it resolve to nothing, with no second step to forget.
     */
    fun findByIdAndProjectId(id: UUID, projectId: UUID): ProjectCategory?

    /**
     * Ordered by name, then id.
     *
     * The tiebreaker is redundant in principle - the functional unique index makes names
     * unique per project - but it costs nothing and means the ordering does not depend on
     * that reasoning staying true, or on how the database collates equal-looking names.
     */
    fun findAllByProjectIdOrderByNameAscIdAsc(projectId: UUID): List<ProjectCategory>

    fun findAllByProjectIdAndIsActiveOrderByNameAscIdAsc(
        projectId: UUID,
        isActive: Boolean,
    ): List<ProjectCategory>

    /**
     * Case-insensitive name lookup, matching the functional unique index on
     * `(project_id, lower(name))`.
     *
     * Used for the friendly duplicate check before an insert. It is not the guarantee - two
     * concurrent creates both pass it - which is why the insert is flushed and the
     * constraint violation is translated into the same conflict.
     */
    @Query("SELECT c FROM ProjectCategory c WHERE c.projectId = :projectId AND lower(c.name) = lower(:name)")
    fun findByProjectIdAndNameIgnoringCase(
        @Param("projectId") projectId: UUID,
        @Param("name") name: String,
    ): ProjectCategory?

    fun countByProjectId(projectId: UUID): Long
}
