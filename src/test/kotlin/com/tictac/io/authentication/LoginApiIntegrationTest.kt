package com.tictac.io.authentication

import com.tictac.io.support.AuthenticatedApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@DisplayName("POST /api/auth/login")
class LoginApiIntegrationTest : AuthenticatedApiTest() {

    @Autowired
    private lateinit var jwtDecoder: JwtDecoder

    @Test
    fun `returns an access token and a refresh token for valid credentials`() {
        registerUser()

        postJson(LOGIN_PATH, credentials())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").isNumber)
    }

    @Test
    fun `the access token identifies the user and carries no personal data`() {
        val userId = registerUser()

        val jwt = jwtDecoder.decode(login().accessToken)

        assertThat(UUID.fromString(jwt.subject)).isEqualTo(userId)
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER)
        // An absolute URI, so Spring's typed accessor works rather than throwing.
        assertThat(jwt.issuer.toString()).isEqualTo(ISSUER)
        assertThat(jwt.expiresAt).isNotNull
        assertThat(jwt.id).isNotBlank

        // Nothing beyond identity and lifetime. A JWT is signed, not encrypted.
        assertThat(jwt.claims.keys).containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "jti")
        assertThat(jwt.claims.values.map { it.toString() })
            .noneMatch { it.contains(DEFAULT_EMAIL) || it.contains(DEFAULT_PASSWORD) || it.contains("John") }
    }

    @Test
    fun `the access token is short lived`() {
        registerUser()

        val jwt = jwtDecoder.decode(login().accessToken)
        val lifetime = java.time.Duration.between(jwt.issuedAt, jwt.expiresAt)

        assertThat(lifetime).isLessThanOrEqualTo(java.time.Duration.ofMinutes(15))
    }

    @Test
    fun `stores a refresh token for the user, hashed`() {
        val userId = registerUser()

        val refreshToken = login().refreshToken

        val stored = refreshTokenRepository.findAll()
        assertThat(stored).hasSize(1)
        assertThat(stored.single().userId).isEqualTo(userId)
        assertThat(stored.single().revokedAt).isNull()
        assertThat(stored.single().expiresAt).isAfter(Instant.now())

        // The raw token must not be recoverable from the database.
        assertThat(stored.single().tokenHash).isNotEqualTo(refreshToken)
        assertThat(stored.single().tokenHash).doesNotContain(refreshToken)
        assertThat(stored.single().tokenHash).hasSize(64)
    }

    @Test
    fun `rejects a wrong password`() {
        registerUser()

        postJson(LOGIN_PATH, credentials(password = "not-the-password"))
            .andExpect(status().isUnauthorized)

        assertThat(refreshTokenRepository.count()).isZero()
    }

    @Test
    fun `rejects an unknown email with a response identical to a wrong password`() {
        registerUser()

        val wrongPassword = postJson(LOGIN_PATH, credentials(password = "not-the-password"))
            .andExpect(status().isUnauthorized)
            .andReturn().response.contentAsString
        val unknownEmail = postJson(LOGIN_PATH, credentials(email = "nobody@example.com"))
            .andExpect(status().isUnauthorized)
            .andReturn().response.contentAsString

        // Byte-identical: the caller cannot tell which addresses are registered.
        assertThat(unknownEmail).isEqualTo(wrongPassword)
    }

    @Test
    fun `rejects a soft deleted account exactly like an unknown one`() {
        registerUser()
        userRepository.findByEmail(DEFAULT_EMAIL)!!
            .also { it.deletedAt = Instant.now() }
            .let { userRepository.saveAndFlush(it) }

        val deleted = postJson(LOGIN_PATH, credentials())
            .andExpect(status().isUnauthorized)
            .andReturn().response.contentAsString
        val unknown = postJson(LOGIN_PATH, credentials(email = "nobody@example.com"))
            .andExpect(status().isUnauthorized)
            .andReturn().response.contentAsString

        assertThat(deleted).isEqualTo(unknown)
        assertThat(refreshTokenRepository.count()).isZero()
    }

    @Test
    fun `rejects an account that has no local password`() {
        registerUser()
        userRepository.findByEmail(DEFAULT_EMAIL)!!
            .also { it.passwordHash = null }
            .let { userRepository.saveAndFlush(it) }

        postJson(LOGIN_PATH, credentials()).andExpect(status().isUnauthorized)
    }

    @Test
    fun `matches the email case insensitively`() {
        registerUser()

        postJson(LOGIN_PATH, credentials(email = "JOHN@Example.COM")).andExpect(status().isOk)
    }

    @Test
    fun `rejects a missing email or password with a validation error`() {
        postJson(LOGIN_PATH, """{"password":"$DEFAULT_PASSWORD"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.email").isNotEmpty)

        postJson(LOGIN_PATH, """{"email":"$DEFAULT_EMAIL"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.password").isNotEmpty)
    }

    @Test
    fun `never echoes the password back`() {
        registerUser()

        val body = postJson(LOGIN_PATH, credentials()).andReturn().response.contentAsString

        assertThat(body).doesNotContain(DEFAULT_PASSWORD)
        assertThat(body.lowercase()).doesNotContain("password")
    }

    @Test
    fun `issues independent sessions for repeated logins`() {
        registerUser()

        val first = login()
        val second = login()

        assertThat(first.refreshToken).isNotEqualTo(second.refreshToken)
        // Logging in on a second device must not sign the first one out.
        assertThat(refreshTokenRepository.findAll().filter { it.revokedAt == null }).hasSize(2)
    }

    private fun credentials(email: String = DEFAULT_EMAIL, password: String = DEFAULT_PASSWORD) =
        """{"email":"$email","password":"$password"}"""

    private companion object {
        const val LOGIN_PATH = "/api/auth/login"
        const val ISSUER = "https://api.test.invalid"
    }
}
