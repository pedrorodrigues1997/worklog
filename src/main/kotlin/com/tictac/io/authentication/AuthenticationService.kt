package com.tictac.io.authentication

import com.tictac.io.authentication.token.AccessTokenIssuer
import com.tictac.io.authentication.token.RefreshTokenService
import com.tictac.io.user.UserRepository
import com.tictac.io.user.normalizeEmail
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

class InvalidCredentialsException : RuntimeException("Invalid email or password")

@Service
class AuthenticationService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val accessTokenIssuer: AccessTokenIssuer,
    private val refreshTokenService: RefreshTokenService,
) {

    /**
     * An Argon2id hash of a value nobody knows, verified against when no real hash is
     * available. Without it, an unknown address would return in microseconds while a
     * known one took the ~40ms of a real hash comparison - a timing difference large
     * enough to enumerate accounts over the network.
     */
    private val decoyHash: String by lazy {
        requireNotNull(passwordEncoder.encode(UUID.randomUUID().toString())) { "encoder returned no hash" }
    }

    @Transactional
    fun login(request: LoginRequest): TokenResponse {
        val email = normalizeEmail(request.email!!)
        val user = userRepository.findByEmail(email)

        // A soft-deleted account, or one with no local password (reserved for future
        // identity-provider sign-in), is treated exactly like a non-existent one.
        val storedHash = user?.takeIf { it.deletedAt == null }?.passwordHash

        val matches = passwordEncoder.matches(request.password!!, storedHash ?: decoyHash)
        if (storedHash == null || !matches) {
            // One exception for every failure mode: wrong password, unknown address,
            // deleted account. The caller learns only that the pair did not work.
            throw InvalidCredentialsException()
        }

        return issueTokensFor(user!!.id!!)
    }

    @Transactional
    fun refresh(request: RefreshRequest): TokenResponse {
        val (userId, refreshToken) = refreshTokenService.rotate(request.refreshToken!!)
        val accessToken = accessTokenIssuer.issue(userId)

        return TokenResponse(
            accessToken = accessToken.value,
            refreshToken = refreshToken.value,
            tokenType = TOKEN_TYPE,
            expiresIn = secondsUntil(accessToken.expiresAt),
        )
    }

    /**
     * Public by design. Requiring a live access token would mean a client whose access
     * token had already expired could not end its session at all - exactly the situation
     * in which logging out matters most. The refresh token is the credential being
     * destroyed, and presenting it is proof enough to destroy it.
     */
    @Transactional
    fun logout(request: LogoutRequest) {
        refreshTokenService.revoke(request.refreshToken!!)
    }

    private fun issueTokensFor(userId: UUID): TokenResponse {
        val accessToken = accessTokenIssuer.issue(userId)
        val refreshToken = refreshTokenService.issueFor(userId)

        return TokenResponse(
            accessToken = accessToken.value,
            refreshToken = refreshToken.value,
            tokenType = TOKEN_TYPE,
            expiresIn = secondsUntil(accessToken.expiresAt),
        )
    }

    private fun secondsUntil(instant: Instant): Long =
        (instant.epochSecond - Instant.now().epochSecond).coerceAtLeast(0)

    private companion object {
        const val TOKEN_TYPE = "Bearer"
    }
}
