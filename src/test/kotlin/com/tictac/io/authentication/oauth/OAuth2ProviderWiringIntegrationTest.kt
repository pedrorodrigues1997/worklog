package com.tictac.io.authentication.oauth

import com.tictac.io.support.PostgresTestContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Proves the OAuth chain is actually registered when Google is configured, and that starting
 * the flow redirects to Google.
 *
 * Only the redirect is checked - nothing here contacts Google. Its endpoints come from
 * Spring's built-in registration, with no issuer-uri, so no OIDC discovery happens even at
 * startup.
 *
 * Runs in its own context because it is the only place OAuth is switched on.
 */
@SpringBootTest(
    properties = [
        "security.oauth2.google.client-id=test-google-client",
        "security.oauth2.google.client-secret=test-google-secret",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig::class)
@DisplayName("OAuth provider wiring")
class OAuth2ProviderWiringIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var clientRegistrationRepository: ClientRegistrationRepository

    @Test
    fun `google is registered`() {
        val registration = clientRegistrationRepository.findByRegistrationId("google")

        assertThat(registration).isNotNull
        assertThat(registration!!.scopes).contains("openid", "email")
        assertThat(registration.clientId).isEqualTo("test-google-client")
    }

    @Test
    fun `starting a google sign-in redirects to google`() {
        val location = mockMvc.perform(get("/oauth2/authorization/google"))
            .andExpect(status().is3xxRedirection)
            .andReturn().response.getHeader(HttpHeaders.LOCATION)!!

        assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth")
        assertThat(location).contains("client_id=test-google-client")
        assertThat(location).contains("response_type=code")
        // CSRF protection for the callback, supplied by the framework rather than by us.
        assertThat(location).contains("state=")
        // The secret never leaves the server.
        assertThat(location).doesNotContain("test-google-secret")
    }

    @Test
    fun `an unconfigured provider is not offered`() {
        val response = mockMvc.perform(get("/oauth2/authorization/facebook")).andReturn().response

        assertThat(response.status).isNotIn(301, 302, 303, 307, 308)
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull()
    }

    @Test
    fun `the rest of the api is unaffected`() {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
    }
}
