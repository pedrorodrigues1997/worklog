package com.tictac.io.authentication.oauth

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Unconditional, unlike [OAuth2SecurityConfig].
 *
 * The login-code table, its service and the exchange endpoint are part of *our* token
 * model rather than any provider's, and the cleanup job depends on them, so they exist
 * whether or not a provider is configured. With none configured no code is ever issued and
 * the exchange endpoint simply has nothing to accept.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuth2Properties::class)
class OAuthConfig
