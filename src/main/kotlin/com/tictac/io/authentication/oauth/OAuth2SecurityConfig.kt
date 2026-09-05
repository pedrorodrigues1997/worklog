package com.tictac.io.authentication.oauth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain

/**
 * The Google-facing half of sign-in, present only when a client id is configured.
 *
 * Two things differ from every other chain in this application. It permits a session,
 * because the authorization request has to survive the round trip to Google and back -
 * that session is created at the start of the flow and destroyed the moment we
 * have an internal user, so the API itself stays stateless. And it has no bearer-token
 * support, because these endpoints are reached by a browser redirect carrying no token of
 * ours.
 *
 * CSRF is off: the authorization endpoint is a GET and the callback is protected by the
 * `state` parameter, which is exactly the CSRF defence OAuth defines for it.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(OAuth2ProviderConfigured::class)
class OAuth2SecurityConfig {

    /**
     * Defining this bean makes Spring Boot's property-driven equivalent back off, which is
     * intended: the registrations are assembled in code from two secrets per provider.
     */
    @Bean
    fun clientRegistrationRepository(properties: OAuth2Properties): ClientRegistrationRepository =
        ClientRegistrations.repositoryFor(properties)

    @Bean
    @Order(0)
    fun oauth2LoginFilterChain(
        http: HttpSecurity,
        successHandler: OAuthLoginSuccessHandler,
        failureHandler: OAuthLoginFailureHandler,
    ): SecurityFilterChain =
        http
            .securityMatcher(AUTHORIZATION_PATHS, CALLBACK_PATHS)
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .oauth2Login {
                it.successHandler(successHandler)
                it.failureHandler(failureHandler)
            }
            .build()

    @Bean
    fun oAuthLoginSuccessHandler(
        identityExtractor: GoogleIdentityExtractor,
        oAuthAuthenticationService: OAuthAuthenticationService,
        loginCodeService: OAuthLoginCodeService,
        properties: OAuth2Properties,
    ) = OAuthLoginSuccessHandler(identityExtractor, oAuthAuthenticationService, loginCodeService, properties)

    @Bean
    fun oAuthLoginFailureHandler(properties: OAuth2Properties) = OAuthLoginFailureHandler(properties)

    companion object {
        /** Where the browser starts the flow: /oauth2/authorization/google. */
        const val AUTHORIZATION_PATHS = "/oauth2/authorization/*"

        /** Where the provider sends it back. */
        const val CALLBACK_PATHS = "/login/oauth2/code/*"
    }
}
