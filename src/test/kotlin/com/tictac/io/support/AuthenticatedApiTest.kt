package com.tictac.io.support

import com.jayway.jsonpath.JsonPath
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Access and refresh token issued by a successful login. */
data class TokenPair(val accessToken: String, val refreshToken: String)

/**
 * Adds account and login helpers. Everything goes through the real HTTP endpoints and
 * the real security filter chain - nothing here bypasses Spring Security, because the
 * behaviour under test *is* the security configuration.
 */
abstract class AuthenticatedApiTest : PostgresIntegrationTest() {

    protected fun registerUser(
        email: String = DEFAULT_EMAIL,
        password: String = DEFAULT_PASSWORD,
        firstName: String = "John",
        lastName: String = "Smith",
    ): UUID {
        val body = postJson(
            "/api/auth/register",
            """{"firstName":"$firstName","lastName":"$lastName","email":"$email","password":"$password"}""",
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        return UUID.fromString(JsonPath.read(body, "$.id"))
    }

    protected fun login(email: String = DEFAULT_EMAIL, password: String = DEFAULT_PASSWORD): TokenPair {
        val body = postJson("/api/auth/login", """{"email":"$email","password":"$password"}""")
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        return TokenPair(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"))
    }

    protected fun registerAndLogin(
        email: String = DEFAULT_EMAIL,
        password: String = DEFAULT_PASSWORD,
    ): Pair<UUID, TokenPair> {
        val userId = registerUser(email, password)
        return userId to login(email, password)
    }

    protected fun postJson(path: String, body: String, accessToken: String? = null): ResultActions {
        val request = post(path).contentType(MediaType.APPLICATION_JSON).content(body)
        accessToken?.let { request.header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        return mockMvc.perform(request)
    }

    protected fun putJson(path: String, body: String, accessToken: String? = null): ResultActions {
        val request = put(path).contentType(MediaType.APPLICATION_JSON).content(body)
        accessToken?.let { request.header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        return mockMvc.perform(request)
    }

    protected fun patchJson(path: String, body: String, accessToken: String? = null): ResultActions {
        val request = patch(path).contentType(MediaType.APPLICATION_JSON).content(body)
        accessToken?.let { request.header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        return mockMvc.perform(request)
    }

    protected fun getRequest(path: String, accessToken: String? = null): ResultActions {
        val request = get(path)
        accessToken?.let { request.header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        return mockMvc.perform(request)
    }

    protected fun deleteRequest(path: String, accessToken: String? = null): ResultActions {
        val request = delete(path)
        accessToken?.let { request.header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        return mockMvc.perform(request)
    }

    protected companion object {
        const val DEFAULT_EMAIL = "john@example.com"
        const val DEFAULT_PASSWORD = "correct-horse-battery"
        const val PROTECTED_PATH = "/api/users/me"
    }
}
