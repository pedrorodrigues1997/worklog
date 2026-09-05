package com.tictac.io.common.ratelimit

import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

data class RateLimitDecision(val allowed: Boolean, val retryAfter: Duration) {
    companion object {
        val ALLOWED = RateLimitDecision(allowed = true, retryAfter = Duration.ZERO)
    }
}

/**
 * A token bucket per client, held in memory.
 *
 * In-process on purpose: a shared counter would mean Redis, and this exists to stop one
 * source hammering an endpoint that runs Argon2 on every call - a per-instance cap does
 * that. The consequence is that the effective limit multiplies by the number of instances,
 * and restarts reset it, so this is the inner of two layers; the edge (Cloudflare) is the
 * one that sees traffic before it costs us anything.
 */
@Component
class TokenBucketRateLimiter(private val properties: RateLimitProperties) {

    private class Bucket(var tokens: Double, var lastRefillNanos: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val tokensPerNano = properties.capacity / properties.window.toNanos().toDouble()

    fun tryConsume(client: String): RateLimitDecision {
        if (!properties.enabled) return RateLimitDecision.ALLOWED

        val now = System.nanoTime()
        var deficit = 0.0

        buckets.compute(client) { _, existing ->
            val bucket = existing ?: Bucket(properties.capacity.toDouble(), now)

            val refill = (now - bucket.lastRefillNanos).coerceAtLeast(0) * tokensPerNano
            bucket.tokens = min(properties.capacity.toDouble(), bucket.tokens + refill)
            bucket.lastRefillNanos = now

            if (bucket.tokens >= 1.0) bucket.tokens -= 1.0 else deficit = 1.0 - bucket.tokens
            bucket
        }

        if (buckets.size > properties.maxTrackedClients) evictReplenished(now)

        if (deficit == 0.0) return RateLimitDecision.ALLOWED

        val retryAfterNanos = ceil(deficit / tokensPerNano).toLong()
        return RateLimitDecision(allowed = false, retryAfter = Duration.ofNanos(retryAfterNanos))
    }

    /**
     * Drops clients whose buckets have refilled completely - they are indistinguishable
     * from a client we have never seen, so forgetting them changes no decision.
     */
    private fun evictReplenished(now: Long) {
        val fullAfterNanos = properties.window.toNanos()
        buckets.entries.removeIf { (_, bucket) -> now - bucket.lastRefillNanos >= fullAfterNanos }
    }

    /** Visible for tests, to assert the eviction bound actually holds. */
    internal fun trackedClients(): Int = buckets.size
}
