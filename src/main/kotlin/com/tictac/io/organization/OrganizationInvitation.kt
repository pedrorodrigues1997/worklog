package com.tictac.io.organization

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * An offer of membership in one organization, addressed to one email.
 *
 * State is three timestamps and nothing else - there is no status column, because pending,
 * expired and accepted are all derivable and a fourth representation would be a fourth thing
 * that can disagree with the others:
 *
 * ```
 * pending  = acceptedAt == null && expiresAt is in the future
 * expired  = acceptedAt == null && expiresAt is in the past
 * accepted = acceptedAt != null
 * ```
 *
 * The invitation carries its own [organizationId], and that is the only place the target
 * organization ever comes from. No acceptance endpoint takes an organization id, so there is
 * no parameter anyone could manipulate to land a membership somewhere else.
 */
@Entity
@Table(name = "organization_invitations")
class OrganizationInvitation(
    @Column(name = "organization_id", nullable = false, updatable = false)
    val organizationId: UUID,

    /** Normalised by [com.tictac.io.user.normalizeEmail], exactly as `users.email` is. */
    @Column(name = "email", nullable = false, updatable = false, length = 320)
    val email: String,

    @Column(name = "invited_by_user_id", nullable = false, updatable = false)
    val invitedByUserId: UUID,

    /** SHA-256 hex of the token. The token itself is never stored. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    val tokenHash: String,

    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    /** Set once, when the invitation is consumed. Never cleared. */
    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null

    @Transient
    fun isAccepted(): Boolean = acceptedAt != null

    /** `@Transient` for the same reason as [com.tictac.io.timetracking.TimeEntry.isRunning]. */
    @Transient
    fun isPendingAt(now: Instant): Boolean = acceptedAt == null && expiresAt.isAfter(now)

    fun acceptAt(now: Instant) {
        acceptedAt = now
    }

    /**
     * Never include [tokenHash]. Entities end up in log lines and exception messages, and
     * that is exactly how a credential leaks - the same rule the other token entities follow.
     */
    override fun toString(): String =
        "OrganizationInvitation(id=$id, organizationId=$organizationId, email=$email, accepted=${acceptedAt != null})"
}

interface OrganizationInvitationRepository : JpaRepository<OrganizationInvitation, UUID> {

    /**
     * The acceptance lookup, with the row locked for update.
     *
     * This is the serialisation point for concurrent acceptance. Two requests presenting the
     * same token both reach here; one takes the lock, the other waits. When the loser
     * resumes, the row is re-read - it is not already in its persistence context, so the
     * values are the winner's committed ones - and `accepted_at` is now set, so it refuses.
     * Without the lock both could read "not yet accepted" and both proceed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM OrganizationInvitation i WHERE i.tokenHash = :tokenHash")
    fun findAndLockByTokenHash(@Param("tokenHash") tokenHash: String): OrganizationInvitation?

    /**
     * The un-accepted invitation for this address, if any - at most one can exist, because
     * the partial unique index in V12 says so.
     *
     * Deliberately not filtered on expiry: the caller needs to tell "already invited" from
     * "invited, but it lapsed", and only one of those is a conflict.
     */
    fun findByOrganizationIdAndEmailAndAcceptedAtIsNull(
        organizationId: UUID,
        email: String,
    ): OrganizationInvitation?

    fun findAllByOrganizationIdAndEmail(organizationId: UUID, email: String): List<OrganizationInvitation>
}
