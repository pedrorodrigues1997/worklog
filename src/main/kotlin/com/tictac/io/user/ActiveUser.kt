package com.tictac.io.user

import com.tictac.io.common.security.CurrentUser
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Resolves the caller's token into a live account.
 *
 * Access tokens are not revocable within their lifetime — deleting an account does not
 * invalidate a token already issued to it, so for up to the access-token TTL a valid
 * signature can outlive the account behind it. Deliberately so: a denylist would add
 * shared state we do not want yet. This lookup is what closes the window instead, and
 * every operation that acts on behalf of a user should go through it rather than trusting
 * the token's `sub` on its own.
 */
@Service
class ActiveUser(
    private val userRepository: UserRepository,
    private val currentUser: CurrentUser,
) {

    @Transactional(readOnly = true)
    fun require(): User =
        userRepository.findById(currentUser.id())
            .filter { it.deletedAt == null }
            .orElseThrow { AccessDeniedException("Account is no longer active") }
}
