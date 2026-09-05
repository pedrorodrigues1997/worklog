package com.tictac.io.authentication.token

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Server-side state for one refresh token.
 *
 * [userId] is a plain column rather than a @ManyToOne to [com.tictac.io.user.User]:
 * the two are separate aggregates, and referencing across a domain boundary by id keeps
 * the modules independent. The foreign key still exists in the database.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    /** SHA-256 hex of the token value. The token itself is never stored. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    val tokenHash: String,

    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "issued_at", nullable = false, updatable = false)
    var issuedAt: Instant = Instant.now()

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "replaced_by_id")
    var replacedById: UUID? = null

    fun isUsableAt(now: Instant): Boolean = revokedAt == null && expiresAt.isAfter(now)

    fun revokeAt(now: Instant) {
        if (revokedAt == null) revokedAt = now
    }

    /** Never include [tokenHash]: it is the credential's only server-side secret. */
    override fun toString(): String = "RefreshToken(id=$id, userId=$userId, revoked=${revokedAt != null})"
}
