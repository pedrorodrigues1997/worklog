package com.tictac.io.organization

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How long an invitation stays usable.
 *
 * Seven days by default, which is where most invitation systems land. It is long enough to
 * survive a weekend and a holiday Monday, and short enough that a link forwarded to the
 * wrong person, or left in an inbox that is later compromised, stops working before it
 * matters. Configurable because a company with a slower onboarding process may reasonably
 * want longer.
 */
@ConfigurationProperties(prefix = "security.invitation")
data class InvitationProperties(
    val ttl: Duration,
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "security.invitation.ttl must be positive" }
    }
}
