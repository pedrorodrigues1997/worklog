package com.tictac.io.authentication.oauth

import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository

/**
 * Builds the provider registration from a client id and secret.
 *
 * Google's endpoints, scopes and grant type come from Spring Security's own catalogue rather
 * than from configuration - they are constants, and constants belong in code where they are
 * covered by tests instead of spread across settings an operator has to get exactly right.
 *
 * Critically there is no `issuer-uri`, so no OIDC discovery call is made: the application
 * must boot without reaching out to Google, or a Google outage becomes a startup failure.
 */
object ClientRegistrations {

    fun repositoryFor(properties: OAuth2Properties): ClientRegistrationRepository {
        require(properties.google.configured) {
            "No OAuth provider is configured; this repository should not have been created"
        }
        return InMemoryClientRegistrationRepository(google(properties.google))
    }

    private fun google(credentials: OAuth2Properties.GoogleCredentials): ClientRegistration =
        CommonOAuth2Provider.GOOGLE
            .getBuilder(OAuthProvider.GOOGLE.registrationId)
            .clientId(credentials.clientId)
            .clientSecret(credentials.clientSecret)
            .build()
}
