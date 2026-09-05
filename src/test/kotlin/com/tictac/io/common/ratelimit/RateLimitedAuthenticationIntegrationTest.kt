package com.tictac.io.common.ratelimit

import com.tictac.io.support.PostgresTestContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.beans.factory.annotation.Autowired

/**
 * Proves the limiter is actually wired into the servlet chain. The unit tests cover the
 * limiting logic itself; a mistake in the FilterRegistrationBean would leave those passing
 * while production ran with no protection at all, which is what this catches.
 *
 * Runs with its own context because it overrides the rate-limit properties that the rest of
 * the suite deliberately disables.
 */
@SpringBootTest(
    properties = [
        "security.rate-limit.enabled=true",
        "security.rate-limit.capacity=3",
        "security.rate-limit.window=1m",
    ],
)
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfig::class)
class RateLimitedAuthenticationIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `throttles repeated authentication attempts from one client`() {
        // Login runs Argon2id on every call, including for addresses that do not exist,
        // so an unauthenticated flood is a CPU cost. The hash stays strong; the rate is
        // what gets capped.
        repeat(3) {
            mockMvc.perform(loginAttempt()).andExpect(status().isUnauthorized)
        }

        mockMvc.perform(loginAttempt())
            .andExpect(status().isTooManyRequests)
            .andExpect { assertThat(it.response.getHeader("Retry-After")).isNotNull() }
    }

    @Test
    fun `leaves non authentication endpoints alone`() {
        repeat(6) { mockMvc.perform(loginAttempt()) }

        // Still refused, but by the security chain rather than the limiter.
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
    }

    private fun loginAttempt() = post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"email":"nobody@example.com","password":"correct-horse-battery"}""")
}
