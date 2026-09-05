package com.tictac.io.common.ratelimit

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

class AuthenticationRateLimitFilterTest {

    private var chainInvocations = 0
    private val chain = FilterChain { _, _ -> chainInvocations++ }

    @Test
    fun `passes requests through until the budget runs out, then answers 429`() {
        val filter = filter(capacity = 2)

        repeat(2) { assertThat(invoke(filter).status).isEqualTo(200) }

        val throttled = invoke(filter)
        assertThat(throttled.status).isEqualTo(429)
        assertThat(throttled.getHeader("Retry-After")?.toInt()).isGreaterThanOrEqualTo(1)
        assertThat(throttled.contentType).startsWith("application/problem+json")
        assertThat(throttled.contentAsString).contains("\"status\":429")
        // The refused request must never reach the endpoint - that is the entire point.
        assertThat(chainInvocations).isEqualTo(2)
    }

    @Test
    fun `only guards the auth endpoints`() {
        val filter = filter(capacity = 1)

        assertThat(invoke(filter, path = "/api/auth/login").status).isEqualTo(200)
        assertThat(invoke(filter, path = "/api/auth/register").status).isEqualTo(429)

        // Same exhausted client, but these paths are not the limiter's business.
        assertThat(invoke(filter, path = "/api/users/me").status).isEqualTo(200)
        assertThat(invoke(filter, path = "/actuator/health").status).isEqualTo(200)
    }

    @Test
    fun `is inert when disabled`() {
        val filter = filter(capacity = 1, enabled = false)

        repeat(20) { assertThat(invoke(filter).status).isEqualTo(200) }
        assertThat(chainInvocations).isEqualTo(20)
    }

    @Test
    fun `budgets by source address`() {
        val filter = filter(capacity = 1)

        assertThat(invoke(filter, ip = "1.2.3.4").status).isEqualTo(200)
        assertThat(invoke(filter, ip = "1.2.3.4").status).isEqualTo(429)
        assertThat(invoke(filter, ip = "5.6.7.8").status).isEqualTo(200)
    }

    @Test
    fun `prefers the configured edge header over the socket address`() {
        // Behind Cloudflare every request arrives from the same few edge addresses, so
        // without this one busy visitor would throttle everyone.
        val filter = filter(capacity = 1, clientIpHeader = "CF-Connecting-IP")

        assertThat(invoke(filter, ip = "10.0.0.1", headerIp = "203.0.113.7").status).isEqualTo(200)
        assertThat(invoke(filter, ip = "10.0.0.1", headerIp = "203.0.113.7").status).isEqualTo(429)
        assertThat(invoke(filter, ip = "10.0.0.1", headerIp = "203.0.113.8").status).isEqualTo(200)
    }

    @Test
    fun `falls back to the socket address when the edge header is absent`() {
        val filter = filter(capacity = 1, clientIpHeader = "CF-Connecting-IP")

        assertThat(invoke(filter, ip = "1.2.3.4").status).isEqualTo(200)
        assertThat(invoke(filter, ip = "1.2.3.4").status).isEqualTo(429)
    }

    private fun invoke(
        filter: OncePerRequestFilter,
        ip: String = "1.2.3.4",
        headerIp: String? = null,
        path: String = "/api/auth/login",
    ): MockHttpServletResponse {
        val request = request(path).apply {
            remoteAddr = ip
            headerIp?.let { addHeader("CF-Connecting-IP", it) }
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, chain)
        return response
    }

    private fun request(uri: String) = MockHttpServletRequest("POST", uri).apply { requestURI = uri }

    private fun filter(
        capacity: Int,
        enabled: Boolean = true,
        clientIpHeader: String = "",
    ): AuthenticationRateLimitFilter {
        val properties = RateLimitProperties(
            enabled = enabled,
            capacity = capacity,
            window = Duration.ofMinutes(1),
            clientIpHeader = clientIpHeader,
            maxTrackedClients = 1000,
        )
        return AuthenticationRateLimitFilter(TokenBucketRateLimiter(properties), properties)
    }
}
