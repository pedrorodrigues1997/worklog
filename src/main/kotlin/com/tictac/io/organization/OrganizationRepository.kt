package com.tictac.io.organization

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OrganizationRepository : JpaRepository<Organization, UUID> {

    /**
     * Soft-deleted organizations are excluded here rather than filtered by the caller, so
     * a deleted tenant cannot be reached by anyone who happens to hold its id.
     */
    fun findByIdAndDeletedAtIsNull(id: UUID): Organization?
}
