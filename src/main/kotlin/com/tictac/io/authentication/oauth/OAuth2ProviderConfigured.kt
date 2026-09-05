package com.tictac.io.authentication.oauth

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * True when at least one provider has a client id configured.
 *
 * OAuth has to be entirely absent when nobody has configured it, or a developer who just
 * cloned the repository could not start the application. Conditioning on the configuration
 * itself - rather than on a separate on/off flag - means there is only one thing to set and
 * no way for the two to disagree.
 *
 * Deliberately a Condition on the Environment rather than @ConditionalOnBean: user
 * configuration is processed before auto-configuration, so the ClientRegistrationRepository
 * bean does not exist yet at the moment this is evaluated.
 */
class OAuth2ProviderConfigured : Condition {

    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        OAuthProvider.entries.any { provider ->
            context.environment
                .getProperty("security.oauth2.${provider.registrationId}.client-id")
                ?.isNotBlank() == true
        }
}
