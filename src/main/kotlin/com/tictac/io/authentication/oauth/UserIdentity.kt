package com.tictac.io.authentication.oauth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * A link between one internal user and one external identity.
 *
 * [userId] is a plain column rather than a @ManyToOne for the same reason as RefreshToken:
 * separate aggregates, referenced by id, with the foreign key still enforced by the
 * database.
 */
@Entity
@Table(name = "user_identities")
class UserIdentity(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 32)
    val provider: OAuthProvider,

    @Column(name = "provider_user_id", nullable = false, updatable = false, length = 255)
    val providerUserId: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun toString(): String = "UserIdentity(id=$id, userId=$userId, provider=$provider)"
}

interface UserIdentityRepository : JpaRepository<UserIdentity, UUID> {
    fun findByProviderAndProviderUserId(provider: OAuthProvider, providerUserId: String): UserIdentity?
    fun findByUserIdAndProvider(userId: UUID, provider: OAuthProvider): UserIdentity?
    fun findAllByUserId(userId: UUID): List<UserIdentity>
}
