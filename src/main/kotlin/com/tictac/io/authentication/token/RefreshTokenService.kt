package com.tictac.io.authentication.token

import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findByTokenHash(tokenHash: String): RefreshToken?
    fun findAllByUserIdAndRevokedAtIsNull(userId: UUID): List<RefreshToken>

    /**
     * Only *expired* rows go. A revoked-but-unexpired row has to stay: it is what makes
     * reuse detection possible, since deleting it would turn a replayed stolen token
     * into an ordinary "unknown token" and lose the theft signal.
     */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    fun deleteExpiredBefore(cutoff: Instant): Int
}

/** A freshly minted refresh token. [value] is the only time the raw token exists server-side. */
data class IssuedRefreshToken(val value: String, val expiresAt: Instant)

class InvalidRefreshTokenException : RuntimeException("Refresh token is invalid or has expired")

@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val refreshTokenRevoker: RefreshTokenRevoker,
    private val properties: JwtProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    /**
     * Refresh tokens are opaque random values, not JWTs. A JWT would be self-validating
     * and therefore impossible to revoke without a server-side lookup anyway - and if we
     * are doing the lookup, the token gains nothing from being a JWT.
     */
    @Transactional
    fun issueFor(userId: UUID): IssuedRefreshToken = create(userId, Instant.now()).second

    /**
     * Exchanges a refresh token for a new one, invalidating the old one (rotation), so a
     * token that leaks is useful to an attacker only until the legitimate client next
     * refreshes.
     */
    @Transactional
    fun rotate(rawToken: String): Pair<UUID, IssuedRefreshToken> {
        val now = Instant.now()
        val presented = refreshTokenRepository.findByTokenHash(hash(rawToken))
            ?: throw InvalidRefreshTokenException()

        if (presented.revokedAt != null) {
            // Why replaced_by_id decides the response: a token that was *rotated away*
            // should never be presented again, so seeing it means two parties hold the
            // same token - the copy was stolen. We cannot tell the thief from the real
            // client, so every session for this user ends; the attacker keeps nothing.
            //
            // A token revoked by logout (or by an earlier cascade) has no replacement.
            // Replaying one is ordinary client confusion, not evidence of theft, and
            // must not sign the user out of their other devices.
            if (presented.replacedById != null) {
                log.warn("Refresh token reuse detected for user {}; revoking all sessions", presented.userId)
                refreshTokenRevoker.revokeAllFor(presented.userId, now)
            }
            throw InvalidRefreshTokenException()
        }

        if (!presented.isUsableAt(now)) {
            throw InvalidRefreshTokenException()
        }

        val (replacement, issued) = create(presented.userId, now)
        presented.revokeAt(now)
        presented.replacedById = replacement.id
        refreshTokenRepository.save(presented)

        return presented.userId to issued
    }

    /**
     * Revokes [rawToken]. Holding the token is the entire authorisation: whoever has it
     * can already mint access tokens with it, so letting them destroy it instead is
     * strictly safer than refusing. Unknown tokens are a silent no-op, so logout never
     * becomes an oracle for which tokens exist.
     */
    @Transactional
    fun revoke(rawToken: String) {
        val token = refreshTokenRepository.findByTokenHash(hash(rawToken)) ?: return

        token.revokeAt(Instant.now())
        refreshTokenRepository.save(token)
    }

    /** Deletes tokens that are past their expiry and can no longer be presented. */
    @Transactional
    fun deleteExpiredBefore(cutoff: Instant): Int = refreshTokenRepository.deleteExpiredBefore(cutoff)

    private fun create(userId: UUID, now: Instant): Pair<RefreshToken, IssuedRefreshToken> {
        val rawToken = generateRawToken()
        val expiresAt = now.plus(properties.refreshTokenTtl)

        val entity = RefreshToken(userId = userId, tokenHash = hash(rawToken), expiresAt = expiresAt)
            .apply { issuedAt = now }

        // Flush so the row (and its generated id) exists before another token can point
        // at it through replaced_by_id.
        val saved = refreshTokenRepository.saveAndFlush(entity)

        return saved to IssuedRefreshToken(rawToken, expiresAt)
    }

    private fun generateRawToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(rawToken: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance(HASH_ALGORITHM).digest(rawToken.toByteArray(Charsets.UTF_8)),
        )

    companion object {
        /** 256 bits from a CSPRNG - far beyond guessing range. */
        const val TOKEN_BYTES = 32
        const val HASH_ALGORITHM = "SHA-256"
    }
}
