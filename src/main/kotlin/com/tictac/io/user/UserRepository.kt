package com.tictac.io.user

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {

    /** [email] must already be normalised by the caller. */
    fun findByEmail(email: String): User?

    /** [email] must already be normalised by the caller. */
    fun existsByEmail(email: String): Boolean

    /**
     * The account, with the row locked for update (`SELECT ... FOR UPDATE`).
     *
     * The interlock between account closure and ownership transfer. Closure locks the
     * closing user's row before asking what they own; a transfer locks the *incoming*
     * owner's row before promoting them. Because both take the same lock first, they
     * cannot both read a stale answer and then both act on it: whichever arrives second
     * sees the first one's committed result and refuses. Without it, closure could check
     * "owns nothing", a transfer could commit ownership to that user, and the account
     * would close as the owner of an active organization - the exact state the two rules
     * exist to prevent.
     *
     * Prefer [ActiveUser.require] for ordinary reads; this is only for the write paths
     * that must not interleave.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    fun findAndLockById(@Param("id") id: UUID): User?
}
