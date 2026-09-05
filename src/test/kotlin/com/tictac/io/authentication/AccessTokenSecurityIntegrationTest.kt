package com.tictac.io.authentication

import com.tictac.io.authentication.token.JwtConfig
import com.tictac.io.support.AuthenticatedApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@DisplayName("Access token enforcement on protected endpoints")
class AccessTokenSecurityIntegrationTest : AuthenticatedApiTest() {

    @Autowired
    private lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `an authenticated request succeeds and resolves the caller from the token`() {
        val (userId, tokens) = registerAndLogin()

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.accessToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.email").value(DEFAULT_EMAIL))
    }

    @Test
    fun `an unauthenticated request is rejected`() {
        registerAndLogin()

        mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an expired token is rejected`() {
        val (userId, _) = registerAndLogin()
        val expired = mint(subject = userId.toString(), expiresAt = Instant.now().minus(1, ChronoUnit.HOURS))

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer $expired"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `a token signed with a different key is rejected`() {
        val (userId, _) = registerAndLogin()

        // Correct claims, correct algorithm, wrong signature - the case that matters if
        // an attacker can guess the payload but not the secret.
        val forged = mint(subject = userId.toString(), encoder = foreignEncoder())

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer $forged"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `a token from another issuer is rejected`() {
        val (userId, _) = registerAndLogin()
        val wrongIssuer = mint(subject = userId.toString(), issuer = "https://someone-else.example")

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer $wrongIssuer"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `a malformed token is rejected`() {
        registerAndLogin()

        listOf("not-a-jwt", "a.b.c", "", "Bearer", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0").forEach { value ->
            mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer $value"))
                .andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `a token whose subject is not a known user is refused`() {
        registerAndLogin()
        val ghost = mint(subject = UUID.randomUUID().toString())

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer $ghost"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `a token belonging to a deleted account is refused`() {
        val (_, tokens) = registerAndLogin()
        userRepository.findByEmail(DEFAULT_EMAIL)!!
            .also { it.deletedAt = Instant.now() }
            .let { userRepository.saveAndFlush(it) }

        // The signature is still valid - the account check is what stops it.
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.accessToken}"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `the caller cannot substitute another user id supplied in the request`() {
        val (ownId, tokens) = registerAndLogin()
        val otherId = registerUser(email = "jane@example.com")

        val body = mockMvc.perform(
            get(PROTECTED_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.accessToken}")
                .param("userId", otherId.toString()),
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains(ownId.toString())
        assertThat(body).doesNotContain(otherId.toString())
        assertThat(body).doesNotContain("jane@example.com")
    }

    private fun mint(
        subject: String,
        issuer: String = "https://api.test.invalid",
        expiresAt: Instant = Instant.now().plus(15, ChronoUnit.MINUTES),
        encoder: JwtEncoder = jwtEncoder,
    ): String {
        val claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(subject)
            .issuedAt(expiresAt.minus(15, ChronoUnit.MINUTES))
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .build()

        return encoder.encode(
            JwtEncoderParameters.from(JwsHeader.with(JwtConfig.ALGORITHM).build(), claims),
        ).tokenValue
    }

    private fun foreignEncoder(): JwtEncoder =
        NimbusJwtEncoder(
            com.nimbusds.jose.jwk.source.ImmutableSecret(
                SecretKeySpec(
                    "a-completely-different-signing-key-000000".toByteArray(Charsets.UTF_8),
                    JwtConfig.KEY_ALGORITHM,
                ),
            ),
        )
}
