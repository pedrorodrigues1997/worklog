package com.tictac.io.common.ratelimit

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfig {

    /**
     * Registered ahead of the security filter chain so a flood is turned away before it
     * reaches anything that costs CPU.
     */
    @Bean
    fun authenticationRateLimitFilter(
        rateLimiter: TokenBucketRateLimiter,
        properties: RateLimitProperties,
    ): FilterRegistrationBean<AuthenticationRateLimitFilter> =
        FilterRegistrationBean(AuthenticationRateLimitFilter(rateLimiter, properties)).apply {
            addUrlPatterns("/api/auth/*")
            order = Ordered.HIGHEST_PRECEDENCE + 10
        }
}
