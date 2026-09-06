package com.tictac.io.organization

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Registers [InvitationProperties], following the same per-feature pattern as `JwtConfig`,
 * `OAuthConfig` and `RateLimitConfig` - the application class has no
 * `@ConfigurationPropertiesScan`, so each properties class is enabled where it belongs.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvitationProperties::class)
class InvitationConfig
