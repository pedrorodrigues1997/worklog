package com.tictac.io.authentication.oauth

import com.jayway.jsonpath.JsonPath
import com.tictac.io.support.AuthenticatedApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant
import java.util.UUID

/**
 * The whole handoff, from "Spring Security has verified an ID token" to "the caller holds
 * our access token".
 *
 * The provider is stubbed by handing the success handler the [OAuth2AuthenticationToken]
 * Spring would have built after a real sign-in - so the authorization-code exchange and ID
 * token validation, the parts we did not write, are the only things not exercised, and no
 * test needs a real Google account.
 */
@DisplayName("OAuth sign-in handoff")
class OAuthLoginHandoffIntegrationTest : AuthenticatedApiTest() {

    @Autowired
    private lateinit var oAuthAuthenticationService: OAuthAuthenticationService

    @Autowired
    private lateinit var loginCodeService: OAuthLoginCodeService

    @Autowired
    private lateinit var properties: OAuth2Properties

    private val successHandler by lazy {
        OAuthLoginSuccessHandler(
            GoogleIdentityExtractor(),
            oAuthAuthenticationService,
            loginCodeService,
            properties,
        )
    }

    @Test
    fun `a provider sign-in ends with our own access and refresh tokens`() {
        val response = signIn()

        assertThat(response.status).isEqualTo(302)
        val code = codeFrom(response)

        val body = postJson(EXCHANGE_PATH, """{"code":"$code"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andReturn().response.contentAsString

        // Identical to what a password login yields - one session model for both.
        val accessToken = JsonPath.read<String>(body, "$.accessToken")
        val userId = userRepository.findByEmail("john@example.com")!!.id!!

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))

        // ...including the refresh token, which rotates like any other.
        val refreshToken = JsonPath.read<String>(body, "$.refreshToken")
        postJson("/api/auth/refresh", """{"refreshToken":"$refreshToken"}""").andExpect(status().isOk)
    }

    @Test
    fun `the redirect carries only a short lived code, never the tokens themselves`() {
        val response = signIn()
        val location = response.getHeader(HttpHeaders.LOCATION)!!

        assertThat(location).startsWith(properties.successRedirectUri)
        assertThat(location).doesNotContain("accessToken")
        assertThat(location).doesNotContain("refreshToken")
        assertThat(location).doesNotContain("eyJ") // no JWT in the URL

        val stored = oAuthLoginCodeRepository.findAll().single()
        // Hashed at rest, and dead within the minute.
        assertThat(stored.codeHash).isNotEqualTo(codeFrom(response))
        assertThat(stored.codeHash).hasSize(64)
        assertThat(stored.expiresAt).isBefore(Instant.now().plusSeconds(120))
    }

    @Test
    fun `a code cannot be used twice`() {
        val code = codeFrom(signIn())

        postJson(EXCHANGE_PATH, """{"code":"$code"}""").andExpect(status().isOk)
        // Anyone who scraped it from the redirect URL is too late.
        postJson(EXCHANGE_PATH, """{"code":"$code"}""").andExpect(status().isUnauthorized)
    }

    @Test
    fun `an unknown code is refused`() {
        postJson(EXCHANGE_PATH, """{"code":"never-issued"}""").andExpect(status().isUnauthorized)
        postJson(EXCHANGE_PATH, "{}").andExpect(status().isBadRequest)
    }

    @Test
    fun `an expired code is refused`() {
        val userId = oAuthAuthenticationService.authenticate(
            OAuthUserIdentity(OAuthProvider.GOOGLE, "g-1", "john@example.com", true, "John", "Smith"),
        )
        val code = loginCodeService.issue(userId)
        oAuthLoginCodeRepository.findAll().single()
            .let { stored ->
                oAuthLoginCodeRepository.delete(stored)
                oAuthLoginCodeRepository.saveAndFlush(
                    OAuthLoginCode(stored.userId, stored.codeHash, Instant.now().minusSeconds(60)),
                )
            }

        postJson(EXCHANGE_PATH, """{"code":"$code"}""").andExpect(status().isUnauthorized)
    }

    @Test
    fun `the exchange endpoint needs no access token`() {
        val code = codeFrom(signIn())

        // The code is the credential; the caller has no token of ours yet by definition.
        postJson(EXCHANGE_PATH, """{"code":"$code"}""").andExpect(status().isOk)
    }

    @Test
    fun `an unverified address is refused at the redirect, creating nothing`() {
        val response = signIn(emailVerified = false)

        assertThat(response.getHeader(HttpHeaders.LOCATION))
            .startsWith(properties.failureRedirectUri)
            .contains("error=oauth_linking_not_allowed")
        assertThat(userRepository.count()).isZero()
        assertThat(oAuthLoginCodeRepository.count()).isZero()
    }

    @Test
    fun `an unusable provider response is refused at the redirect`() {
        val response = signIn(claims = mapOf("sub" to "g-1")) // no email

        assertThat(response.getHeader(HttpHeaders.LOCATION))
            .startsWith(properties.failureRedirectUri)
            .contains("error=oauth_invalid_identity")
        assertThat(userRepository.count()).isZero()
    }

    @Test
    fun `a registration we do not support is refused`() {
        // Belt and braces on the single supported provider: even if a registration were
        // added to the filter chain, claims from it would not be accepted here until an
        // extractor existed that knows how that provider signals a verified address.
        val response = signIn(registrationId = "microsoft")

        assertThat(response.getHeader(HttpHeaders.LOCATION))
            .startsWith(properties.failureRedirectUri)
            .contains("error=oauth_invalid_identity")
        assertThat(userRepository.count()).isZero()
    }

    @Test
    fun `the session carrying the provider exchange is destroyed`() {
        val request = MockHttpServletRequest().apply { getSession(true) }
        val response = MockHttpServletResponse()

        successHandler.onAuthenticationSuccess(request, response, authenticationToken())

        // The provider's own tokens lived there; nothing of ours needs it.
        assertThat(request.getSession(false)).isNull()
    }

    @Test
    fun `a provider failure redirects without leaking the reason`() {
        val response = MockHttpServletResponse()

        OAuthLoginFailureHandler(properties).onAuthenticationFailure(
            MockHttpServletRequest(),
            response,
            BadCredentialsException("invalid_grant: the code was already redeemed by 10.0.0.9"),
        )

        val location = response.getHeader(HttpHeaders.LOCATION)!!
        assertThat(location).startsWith(properties.failureRedirectUri)
        assertThat(location).contains("error=oauth_failed")
        assertThat(location).doesNotContain("invalid_grant")
        assertThat(location).doesNotContain("10.0.0.9")
    }

    private fun signIn(
        emailVerified: Boolean = true,
        claims: Map<String, Any>? = null,
        registrationId: String = "google",
    ): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        successHandler.onAuthenticationSuccess(
            MockHttpServletRequest(),
            response,
            authenticationToken(emailVerified, claims, registrationId),
        )
        return response
    }

    private fun authenticationToken(
        emailVerified: Boolean = true,
        claims: Map<String, Any>? = null,
        registrationId: String = "google",
    ): Authentication {
        val effective = claims ?: mapOf(
            "sub" to "google-sub-1",
            "email" to "john@example.com",
            "email_verified" to emailVerified,
            "given_name" to "John",
            "family_name" to "Smith",
        )
        val idToken = OidcIdToken(
            "id-token-value",
            Instant.now(),
            Instant.now().plusSeconds(300),
            effective + mapOf("iss" to "https://accounts.google.test", "aud" to listOf("client-id")),
        )
        return OAuth2AuthenticationToken(
            DefaultOidcUser(emptyList(), idToken, "sub"),
            emptyList(),
            registrationId,
        )
    }

    private fun codeFrom(response: MockHttpServletResponse): String =
        UriComponentsBuilder.fromUriString(response.getHeader(HttpHeaders.LOCATION)!!)
            .build()
            .queryParams
            .getFirst("code")!!

    private companion object {
        const val EXCHANGE_PATH = "/api/auth/oauth/exchange"

        @Suppress("unused")
        val UNUSED: UUID? = null
    }
}
