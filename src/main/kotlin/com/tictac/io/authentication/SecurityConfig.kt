package com.tictac.io.authentication

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    /**
     * The credential-bearing endpoints, matched by method and path so only the POSTs are
     * covered - `GET /api/auth/register` still falls through to the default-deny chain.
     *
     * This chain deliberately has no bearer-token support at all. If it did, a request
     * carrying a stale `Authorization` header would be rejected during authentication
     * before ever reaching the handler, even though the endpoint is public - and clients
     * routinely attach their access token to every request without thinking. That would
     * make logging out impossible exactly when the access token has expired, which is when
     * it matters. Here the header is simply ignored; these endpoints authenticate
     * themselves, by credentials for login and by the refresh token for refresh and logout.
     */
    @Bean
    @Order(1)
    fun publicAuthenticationFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .securityMatcher(
                OrRequestMatcher(
                    PUBLIC_POST_PATHS.map { PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, it) },
                ),
            )
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()

    /**
     * Everything else. Default-deny: a valid access token is required unless listed below,
     * so a new endpoint is protected by accident rather than exposed by accident.
     *
     * Authentication is stateless - the token is the entire session. No HTTP session is
     * created, which is also why CSRF protection is off: there is no ambient credential a
     * browser could be tricked into attaching, since the token has to be placed in the
     * Authorization header by script. Revisit both if we ever move tokens into cookies.
     */
    @Bean
    @Order(2)
    fun securityFilterChain(http: HttpSecurity, jwtDecoder: JwtDecoder): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                it.anyRequest().authenticated()
            }
            // Bearer tokens only. The decoder pins the signing key, algorithm and issuer,
            // and rejects anything expired; failures surface as 401 with a Bearer
            // challenge, never as a redirect to a login page.
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { it.decoder(jwtDecoder) }
            }
            .build()

    companion object {
        const val REGISTER_PATH = "/api/auth/register"
        const val LOGIN_PATH = "/api/auth/login"
        const val REFRESH_PATH = "/api/auth/refresh"
        const val LOGOUT_PATH = "/api/auth/logout"
        const val OAUTH_EXCHANGE_PATH = "/api/auth/oauth/exchange"

        /**
         * All of these are authenticated by something other than an access token:
         * credentials for login, a refresh token for refresh and logout, a single-use code
         * for the OAuth exchange, nothing at all for register.
         */
        val PUBLIC_POST_PATHS =
            listOf(REGISTER_PATH, LOGIN_PATH, REFRESH_PATH, LOGOUT_PATH, OAUTH_EXCHANGE_PATH)
    }
}
