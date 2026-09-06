package com.tictac.io.organization

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface OrganizationRepository : JpaRepository<Organization, UUID> {

    /**
     * Soft-deleted organizations are excluded here rather than filtered by the caller, so
     * a deleted tenant cannot be reached by anyone who happens to hold its id.
     */
    fun findByIdAndDeletedAtIsNull(id: UUID): Organization?

    /**
     * The organization row, locked for update.
     *
     * The serialisation point for **licence allocation**, and therefore for billing.
     * Counting who holds a licence and then acting on the count is only safe if nothing else
     * can change it in between - two invitations issued at the same instant would otherwise
     * both be told the same single vacant licence is theirs, and the organization would end up
     * with more people in it than it holds licences for.
     *
     * It is also the row the licence count itself lives on, so the lock and the number it
     * protects are the same row: no second lock can be forgotten.
     *
     * The organization row rather than the subscription row because it always exists: an
     * organization holding only its free included licence has no subscription, and the
     * free-to-paid transition is exactly the moment that needs serialising.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Organization o WHERE o.id = :id")
    fun findAndLockById(@Param("id") id: UUID): Organization?
}
