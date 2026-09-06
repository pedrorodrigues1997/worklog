package com.tictac.io.authentication.token

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Bulk revocation, in its own transaction and its own bean.
 *
 * Both details matter. Reuse detection revokes a user's sessions and *then* fails the
 * request; if that revocation shared the caller's transaction it would be rolled back
 * along with the rejected refresh, and the attacker's stolen token would survive the
 * response that was supposed to kill it. REQUIRES_NEW commits it independently, and a
 * separate bean is what makes the annotation take effect - a call to a @Transactional
 * method on `this` goes straight past the proxy that implements it.
 */
@Component
class RefreshTokenRevoker(
    private val refreshTokenRepository: RefreshTokenRepository,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revokeAllFor(userId: UUID, now: Instant = Instant.now()) {
        revokeAll(userId, now)
    }

    /**
     * The same revocation, sharing the caller's transaction.
     *
     * Account closure wants the opposite guarantee to reuse detection: closing an account
     * and ending its sessions must succeed or fail together. Revoking independently would
     * mean a closure that rolled back still signed the user out of everything, and - worse
     * in the other direction - the account row could commit while the revocation did not.
     */
    @Transactional
    fun revokeAllForInCurrentTransaction(userId: UUID, now: Instant = Instant.now()) {
        revokeAll(userId, now)
    }

    private fun revokeAll(userId: UUID, now: Instant) {
        val active = refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)
        if (active.isEmpty()) return

        active.forEach { it.revokeAt(now) }
        refreshTokenRepository.saveAll(active)
    }
}
