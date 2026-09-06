package com.tictac.io.authentication.oauth

import com.tictac.io.common.security.SecureToken
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "oauth_login_codes")
class OAuthLoginCode(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    /** SHA-256 hex of the code. The code itself is never stored. */
    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    val codeHash: String,

    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null

    override fun toString(): String = "OAuthLoginCode(id=$id, userId=$userId)"
}

interface OAuthLoginCodeRepository : JpaRepository<OAuthLoginCode, UUID> {
    fun findByCodeHash(codeHash: String): OAuthLoginCode?

    @Modifying
    @Query("delete from OAuthLoginCode c where c.expiresAt < :cutoff")
    fun deleteExpiredBefore(cutoff: Instant): Int
}

class InvalidLoginCodeException : RuntimeException("Login code is invalid, expired or already used")

@Service
class OAuthLoginCodeService(
    private val repository: OAuthLoginCodeRepository,
    private val properties: OAuth2Properties,
) {

    @Transactional
    fun issue(userId: UUID): String {
        val code = generateCode()
        repository.saveAndFlush(
            OAuthLoginCode(
                userId = userId,
                codeHash = hash(code),
                expiresAt = Instant.now().plus(properties.loginCodeTtl),
            ),
        )
        return code
    }

    /**
     * Single use: the code is marked consumed before the caller gets the user id back, so
     * a code captured from the redirect URL is worthless the moment the real client has
     * used it. Every failure mode returns the same exception - the caller learns only that
     * the code did not work.
     */
    @Transactional
    fun consume(rawCode: String): UUID {
        val now = Instant.now()
        val code = repository.findByCodeHash(hash(rawCode)) ?: throw InvalidLoginCodeException()

        if (code.consumedAt != null || !code.expiresAt.isAfter(now)) {
            throw InvalidLoginCodeException()
        }

        code.consumedAt = now
        repository.save(code)
        return code.userId
    }

    @Transactional
    fun deleteExpiredBefore(cutoff: Instant): Int = repository.deleteExpiredBefore(cutoff)

    private fun generateCode(): String = SecureToken.generate()

    private fun hash(rawCode: String): String = SecureToken.hash(rawCode)
}
