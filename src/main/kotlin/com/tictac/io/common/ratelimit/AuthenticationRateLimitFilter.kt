package com.tictac.io.common.ratelimit

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import kotlin.math.ceil

/**
 * Throttles every endpoint under `/api/auth`, per client.
 *
 * These are the only endpoints an unauthenticated caller can reach, and login and register
 * both run Argon2id - roughly 40ms of CPU and 16MB of memory each, by design. Without a
 * cap, that cost is a denial-of-service lever that needs no credentials at all. The answer
 * is to limit the request rate, never to weaken the hash.
 */
class AuthenticationRateLimitFilter(
    private val rateLimiter: TokenBucketRateLimiter,
    private val properties: RateLimitProperties,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.enabled || !request.requestURI.startsWith(PROTECTED_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val decision = rateLimiter.tryConsume(clientOf(request))
        if (decision.allowed) {
            filterChain.doFilter(request, response)
            return
        }

        // Round up: Duration.toSeconds() truncates, and a Retry-After that expires before
        // the bucket has actually refilled just invites the client straight back into a 429.
        val retryAfterSeconds = ceil(decision.retryAfter.toNanos() / NANOS_PER_SECOND).toLong().coerceAtLeast(1)
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        // Written by hand: this filter runs before the DispatcherServlet, so there is no
        // message converter here. Shape matches the ProblemDetail bodies used elsewhere.
        response.writer.write(
            "{\"type\":\"about:blank\"," +
                "\"title\":\"Too many requests\"," +
                "\"status\":429," +
                "\"detail\":\"Too many authentication requests. Try again shortly.\"," +
                "\"instance\":\"" + request.requestURI + "\"}",
        )
    }

    private fun clientOf(request: HttpServletRequest): String {
        if (properties.clientIpHeader.isNotBlank()) {
            request.getHeader(properties.clientIpHeader)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        }
        return request.remoteAddr ?: UNKNOWN_CLIENT
    }

    private companion object {
        const val PROTECTED_PREFIX = "/api/auth/"

        /** All unattributable callers share one bucket, which is the conservative choice. */
        const val UNKNOWN_CLIENT = "unknown"

        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
