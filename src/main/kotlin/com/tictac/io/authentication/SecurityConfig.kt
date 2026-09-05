package com.tictac.io.authentication

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    /**
     * Default-deny: everything requires authentication unless listed below. New
     * endpoints are therefore protected by default rather than accidentally public.
     *
     * CSRF is disabled because there is no cookie- or session-based authentication.
     * This must be revisited the moment we introduce cookie-backed sessions.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, REGISTER_PATH).permitAll()
                it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                it.anyRequest().authenticated()
            }
            // Return a bare 401 instead of a login redirect or a Basic auth challenge.
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .build()

    companion object {
        const val REGISTER_PATH = "/api/auth/register"
    }
}
