package com.tictac.io.authentication.token

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Expired refresh tokens are dead weight - they cannot be presented successfully, they
 * only grow the table. This removes them on a schedule.
 *
 * Safe to run on every instance: the delete is idempotent, so overlapping runs across a
 * scaled-out deployment cost a little duplicated work and nothing else. No distributed
 * lock needed at this size.
 */
@Component
class RefreshTokenCleanupJob(
    private val refreshTokenService: RefreshTokenService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${security.refresh-token-cleanup.cron}")
    fun deleteExpiredTokens() {
        val deleted = refreshTokenService.deleteExpiredBefore(Instant.now())
        if (deleted > 0) log.info("Deleted {} expired refresh tokens", deleted)
    }
}
