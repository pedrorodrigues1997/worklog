package com.tictac.io.authentication.token

import com.tictac.io.support.PostgresIntegrationTest
import com.tictac.io.user.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class RefreshTokenCleanupJobIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var cleanupJob: RefreshTokenCleanupJob

    @Test
    fun `deletes expired tokens and keeps everything still presentable`() {
        val userId = persistUser()

        val expired = persistToken(userId, expiresAt = Instant.now().minus(1, ChronoUnit.DAYS))
        val active = persistToken(userId, expiresAt = Instant.now().plus(30, ChronoUnit.DAYS))
        // Revoked but not yet expired: this row is what makes reuse detection work, so it
        // has to survive until its natural expiry.
        val revokedButLive = persistToken(userId, expiresAt = Instant.now().plus(30, ChronoUnit.DAYS))
            .also { it.revokeAt(Instant.now()); refreshTokenRepository.saveAndFlush(it) }

        cleanupJob.deleteExpiredTokens()

        val remaining = refreshTokenRepository.findAll().mapNotNull { it.id }
        assertThat(remaining).containsExactlyInAnyOrder(active.id, revokedButLive.id)
        assertThat(remaining).doesNotContain(expired.id)
    }

    @Test
    fun `is safe to run when there is nothing to delete`() {
        val userId = persistUser()
        persistToken(userId, expiresAt = Instant.now().plus(30, ChronoUnit.DAYS))

        // Overlapping runs across instances are expected, so a no-op run must be harmless.
        repeat(3) { cleanupJob.deleteExpiredTokens() }

        assertThat(refreshTokenRepository.count()).isEqualTo(1)
    }

    private fun persistUser(): UUID =
        userRepository.saveAndFlush(
            User(
                firstName = "John",
                lastName = "Smith",
                email = "cleanup-${UUID.randomUUID()}@example.com",
                passwordHash = "{argon2}stub",
            ),
        ).id!!

    private fun persistToken(userId: UUID, expiresAt: Instant): RefreshToken =
        refreshTokenRepository.saveAndFlush(
            RefreshToken(
                userId = userId,
                tokenHash = UUID.randomUUID().toString().replace("-", "").repeat(2),
                expiresAt = expiresAt,
            ),
        )
}
