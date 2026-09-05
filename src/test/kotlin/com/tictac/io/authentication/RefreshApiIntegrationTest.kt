package com.tictac.io.authentication

import com.tictac.io.support.AuthenticatedApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit

@DisplayName("POST /api/auth/refresh")
class RefreshApiIntegrationTest : AuthenticatedApiTest() {

    @Test
    fun `exchanges a valid refresh token for a working access token`() {
        val (userId, tokens) = registerAndLogin()

        val body = postJson(REFRESH_PATH, refreshBody(tokens.refreshToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andReturn().response.contentAsString

        val newAccessToken = com.jayway.jsonpath.JsonPath.read<String>(body, "$.accessToken")
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer $newAccessToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
    }

    @Test
    fun `rotates the refresh token, so the presented one cannot be used again`() {
        val (_, tokens) = registerAndLogin()

        val body = postJson(REFRESH_PATH, refreshBody(tokens.refreshToken))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val rotated = com.jayway.jsonpath.JsonPath.read<String>(body, "$.refreshToken")

        assertThat(rotated).isNotEqualTo(tokens.refreshToken)

        postJson(REFRESH_PATH, refreshBody(tokens.refreshToken)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `records the rotation chain`() {
        val (_, tokens) = registerAndLogin()

        postJson(REFRESH_PATH, refreshBody(tokens.refreshToken)).andExpect(status().isOk)

        val all = refreshTokenRepository.findAll()
        assertThat(all).hasSize(2)
        val old = all.single { it.revokedAt != null }
        val new = all.single { it.revokedAt == null }
        assertThat(old.replacedById).isEqualTo(new.id)
    }

    @Test
    fun `replaying a rotated token revokes every session for that user`() {
        // Reuse of an already-rotated token means the token leaked, or the client is
        // confused. We cannot tell which, so we end all sessions rather than let a thief
        // keep refreshing alongside the real user.
        val (_, first) = registerAndLogin()
        val second = login()

        val body = postJson(REFRESH_PATH, refreshBody(first.refreshToken))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val rotated = com.jayway.jsonpath.JsonPath.read<String>(body, "$.refreshToken")

        postJson(REFRESH_PATH, refreshBody(first.refreshToken)).andExpect(status().isUnauthorized)

        assertThat(refreshTokenRepository.findAll().filter { it.revokedAt == null }).isEmpty()
        postJson(REFRESH_PATH, refreshBody(rotated)).andExpect(status().isUnauthorized)
        postJson(REFRESH_PATH, refreshBody(second.refreshToken)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `rejects an unknown refresh token`() {
        registerAndLogin()

        postJson(REFRESH_PATH, refreshBody("not-a-real-token")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `rejects an expired refresh token`() {
        val (_, tokens) = registerAndLogin()
        refreshTokenRepository.findAll().single()
            .let { stored ->
                // The entity's expiry is immutable, so re-age the row directly.
                refreshTokenRepository.delete(stored)
                refreshTokenRepository.saveAndFlush(
                    com.tictac.io.authentication.token.RefreshToken(
                        userId = stored.userId,
                        tokenHash = stored.tokenHash,
                        expiresAt = Instant.now().minus(1, ChronoUnit.DAYS),
                    ),
                )
            }

        postJson(REFRESH_PATH, refreshBody(tokens.refreshToken)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `rejects a missing refresh token with a validation error`() {
        postJson(REFRESH_PATH, "{}")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.refreshToken").isNotEmpty)
    }

    @Test
    fun `is reachable without an access token`() {
        val (_, tokens) = registerAndLogin()

        // By design: the whole point of refresh is to recover once the access token has
        // expired, so requiring one would make the endpoint useless.
        postJson(REFRESH_PATH, refreshBody(tokens.refreshToken)).andExpect(status().isOk)
    }

    @Test
    fun `an access token cannot be used as a refresh token`() {
        val (_, tokens) = registerAndLogin()

        postJson(REFRESH_PATH, refreshBody(tokens.accessToken)).andExpect(status().isUnauthorized)
    }

    private fun refreshBody(token: String) = """{"refreshToken":"$token"}"""

    private companion object {
        const val REFRESH_PATH = "/api/auth/refresh"
    }
}
