package com.tictac.io.authentication

import com.tictac.io.support.AuthenticatedApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("POST /api/auth/logout")
class LogoutApiIntegrationTest : AuthenticatedApiTest() {

    @Test
    fun `revokes the refresh token so it can no longer be exchanged`() {
        val (_, tokens) = registerAndLogin()

        postJson(LOGOUT_PATH, logoutBody(tokens.refreshToken), tokens.accessToken)
            .andExpect(status().isNoContent)

        assertThat(refreshTokenRepository.findAll().single().revokedAt).isNotNull

        postJson(REFRESH_PATH, logoutBody(tokens.refreshToken)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `works without an access token`() {
        val (_, tokens) = registerAndLogin()

        // The situation logout matters most in: the access token has already expired, and
        // the client still needs to end its session. The refresh token being destroyed is
        // the credential, and presenting it is proof enough to destroy it.
        postJson(LOGOUT_PATH, logoutBody(tokens.refreshToken))
            .andExpect(status().isNoContent)

        assertThat(refreshTokenRepository.findAll().single().revokedAt).isNotNull
        postJson(REFRESH_PATH, logoutBody(tokens.refreshToken)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `works with an expired access token`() {
        val (_, tokens) = registerAndLogin()

        postJson(LOGOUT_PATH, logoutBody(tokens.refreshToken), accessToken = "expired.rubbish.token")
            .andExpect(status().isNoContent)

        assertThat(refreshTokenRepository.findAll().single().revokedAt).isNotNull
    }

    @Test
    fun `revokes only the presented token, leaving other accounts untouched`() {
        val (_, mine) = registerAndLogin(email = "mine@example.com")
        val (_, theirs) = registerAndLogin(email = "theirs@example.com")

        postJson(LOGOUT_PATH, logoutBody(mine.refreshToken)).andExpect(status().isNoContent)

        postJson(REFRESH_PATH, logoutBody(mine.refreshToken)).andExpect(status().isUnauthorized)
        postJson(REFRESH_PATH, logoutBody(theirs.refreshToken)).andExpect(status().isOk)
    }

    @Test
    fun `logging out one session leaves the accounts other sessions alive`() {
        val (_, first) = registerAndLogin()
        val second = login()

        postJson(LOGOUT_PATH, logoutBody(first.refreshToken)).andExpect(status().isNoContent)

        // Replaying the logged-out token is rejected, but must NOT be mistaken for token
        // theft: only a token that was rotated away carries that signal, so the other
        // device stays signed in.
        postJson(REFRESH_PATH, logoutBody(first.refreshToken)).andExpect(status().isUnauthorized)
        postJson(REFRESH_PATH, logoutBody(second.refreshToken)).andExpect(status().isOk)
    }

    @Test
    fun `is idempotent and reveals nothing about unknown tokens`() {
        val (_, tokens) = registerAndLogin()

        val first = postJson(LOGOUT_PATH, logoutBody(tokens.refreshToken))
            .andExpect(status().isNoContent).andReturn().response
        val again = postJson(LOGOUT_PATH, logoutBody(tokens.refreshToken))
            .andExpect(status().isNoContent).andReturn().response
        val nonsense = postJson(LOGOUT_PATH, logoutBody("never-existed"))
            .andExpect(status().isNoContent).andReturn().response

        assertThat(first.contentAsString).isEqualTo(again.contentAsString)
        assertThat(again.contentAsString).isEqualTo(nonsense.contentAsString)
    }

    @Test
    fun `rejects a missing refresh token with a validation error`() {
        postJson(LOGOUT_PATH, "{}").andExpect(status().isBadRequest)
    }

    private fun logoutBody(token: String) = """{"refreshToken":"$token"}"""

    private companion object {
        const val LOGOUT_PATH = "/api/auth/logout"
        const val REFRESH_PATH = "/api/auth/refresh"
    }
}
