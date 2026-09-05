package com.tictac.io.common.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "security.rate-limit")
data class RateLimitProperties(

    val enabled: Boolean,

    /** Requests allowed per [window] per client, and the maximum burst. */
    val capacity: Int,

    val window: Duration,

    /**
     * Header carrying the real client address, for when a proxy sits in front. Empty
     * means trust the socket address instead.
     *
     * This must only ever name a header the edge *overwrites*, never one it appends to:
     * `CF-Connecting-IP` behind Cloudflare is safe, `X-Forwarded-For` is not, because a
     * caller can prepend whatever they like to it and get a fresh bucket per request.
     */
    val clientIpHeader: String,

    /**
     * Cap on tracked clients, so the limiter cannot itself be turned into a memory
     * exhaustion attack by rotating source addresses.
     */
    val maxTrackedClients: Int,
) {
    init {
        require(capacity > 0) { "security.rate-limit.capacity must be positive" }
        require(!window.isNegative && !window.isZero) { "security.rate-limit.window must be positive" }
        require(maxTrackedClients > 0) { "security.rate-limit.max-tracked-clients must be positive" }
    }
}
