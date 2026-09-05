package com.tictac.io.authentication.token

import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.Assertions.assertThatNoException
import org.junit.jupiter.api.Test
import java.time.Duration

class JwtPropertiesTest {

    @Test
    fun `rejects a signing key that is too short for HS256`() {
        // Fail at startup, not on the first forged token.
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { properties(secret = "too-short") }
            .withMessageContaining("at least 32 bytes")
    }

    @Test
    fun `rejects a non positive token lifetime`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { properties(accessTokenTtl = Duration.ZERO) }
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { properties(refreshTokenTtl = Duration.ofMinutes(-1)) }
    }

    @Test
    fun `rejects an issuer that is not an absolute uri`() {
        // A bare name like "io-backend" is the same in every environment and tells nobody
        // which deployment minted a token, and Spring's Jwt.getIssuer() throws on it.
        listOf("", "   ", "io-backend", "/api").forEach { issuer ->
            assertThatExceptionOfType(IllegalArgumentException::class.java)
                .isThrownBy { properties(issuer = issuer) }
                .withMessageContaining("absolute URI")
        }
    }

    @Test
    fun `rejects a negative clock skew`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { properties(clockSkew = Duration.ofSeconds(-1)) }
    }

    @Test
    fun `accepts a key of at least 32 bytes`() {
        assertThatNoException().isThrownBy { properties() }
    }

    private fun properties(
        secret: String = "0123456789012345678901234567890123456789",
        issuer: String = "https://api.test.invalid",
        accessTokenTtl: Duration = Duration.ofMinutes(15),
        refreshTokenTtl: Duration = Duration.ofDays(30),
        clockSkew: Duration = Duration.ofSeconds(5),
    ) = JwtProperties(secret, issuer, accessTokenTtl, refreshTokenTtl, clockSkew)
}
