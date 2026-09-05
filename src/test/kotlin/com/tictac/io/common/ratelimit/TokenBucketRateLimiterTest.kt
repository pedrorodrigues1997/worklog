package com.tictac.io.common.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class TokenBucketRateLimiterTest {

    @Test
    fun `allows requests up to the capacity and refuses the next one`() {
        val limiter = TokenBucketRateLimiter(properties(capacity = 3))

        repeat(3) { assertThat(limiter.tryConsume("1.2.3.4").allowed).isTrue() }

        val refused = limiter.tryConsume("1.2.3.4")
        assertThat(refused.allowed).isFalse()
        assertThat(refused.retryAfter).isPositive()
        assertThat(refused.retryAfter).isLessThanOrEqualTo(Duration.ofMinutes(1))
    }

    @Test
    fun `budgets each client separately`() {
        val limiter = TokenBucketRateLimiter(properties(capacity = 1))

        assertThat(limiter.tryConsume("1.2.3.4").allowed).isTrue()
        assertThat(limiter.tryConsume("1.2.3.4").allowed).isFalse()
        // One noisy address must not lock everyone else out.
        assertThat(limiter.tryConsume("5.6.7.8").allowed).isTrue()
    }

    @Test
    fun `refills over time`() {
        // A 3-per-30ms bucket refills a token roughly every 10ms.
        val limiter = TokenBucketRateLimiter(properties(capacity = 3, window = Duration.ofMillis(30)))

        repeat(3) { limiter.tryConsume("1.2.3.4") }
        assertThat(limiter.tryConsume("1.2.3.4").allowed).isFalse()

        Thread.sleep(50)

        assertThat(limiter.tryConsume("1.2.3.4").allowed).isTrue()
    }

    @Test
    fun `does not accumulate more than the capacity while idle`() {
        val limiter = TokenBucketRateLimiter(properties(capacity = 2, window = Duration.ofMillis(20)))

        Thread.sleep(200) // Long enough to refill many times over.

        assertThat(limiter.tryConsume("1.2.3.4").allowed).isTrue()
        assertThat(limiter.tryConsume("1.2.3.4").allowed).isTrue()
        assertThat(limiter.tryConsume("1.2.3.4").allowed).isFalse()
    }

    @Test
    fun `forgets clients once their bucket has refilled, bounding memory`() {
        // Otherwise rotating source addresses would turn the limiter itself into a way to
        // exhaust the heap.
        val limiter = TokenBucketRateLimiter(
            properties(capacity = 1, window = Duration.ofMillis(1), maxTrackedClients = 5),
        )

        repeat(200) { limiter.tryConsume("client-$it") }
        Thread.sleep(20)
        limiter.tryConsume("trigger-eviction")

        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(10)
    }

    @Test
    fun `lets everything through when disabled`() {
        val limiter = TokenBucketRateLimiter(properties(capacity = 1, enabled = false))

        repeat(50) { assertThat(limiter.tryConsume("1.2.3.4").allowed).isTrue() }
    }

    private fun properties(
        capacity: Int = 20,
        window: Duration = Duration.ofMinutes(1),
        enabled: Boolean = true,
        maxTrackedClients: Int = 1000,
    ) = RateLimitProperties(
        enabled = enabled,
        capacity = capacity,
        window = window,
        clientIpHeader = "",
        maxTrackedClients = maxTrackedClients,
    )
}
