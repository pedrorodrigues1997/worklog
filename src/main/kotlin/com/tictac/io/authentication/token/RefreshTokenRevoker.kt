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
        val active = refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)
        if (active.isEmpty()) return

        active.forEach { it.revokeAt(now) }
        refreshTokenRepository.saveAll(active)
    }
}
