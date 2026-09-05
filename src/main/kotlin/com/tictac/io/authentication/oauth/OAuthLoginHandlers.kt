package com.tictac.io.authentication.oauth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.web.util.UriComponentsBuilder

/**
 * The join between the provider's flow and ours.
 *
 * Spring Security has by this point run the authorization-code exchange and validated the
 * ID token's signature, audience and expiry. What is left is our half: map the verified
 * claims to an internal user id, and hand the browser something it can trade for our own
 * tokens - never the provider's, which stay on this side and are simply discarded.
 */
class OAuthLoginSuccessHandler(
    private val identityExtractor: GoogleIdentityExtractor,
    private val oAuthAuthenticationService: OAuthAuthenticationService,
    private val loginCodeService: OAuthLoginCodeService,
    private val properties: OAuth2Properties,
) : AuthenticationSuccessHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val redirect = try {
            successRedirect(authentication)
        } catch (ex: InvalidOAuthIdentityException) {
            log.warn("Rejected OAuth sign-in: {}", ex.message)
            errorRedirect(INVALID_IDENTITY)
        } catch (ex: OAuthLinkingNotAllowedException) {
            log.warn("Refused to link OAuth identity: {}", ex.message)
            errorRedirect(LINKING_NOT_ALLOWED)
        }

        // The provider's tokens live in this session and are of no further use to us; the
        // session existed only to carry the authorization request across the redirect.
        request.getSession(false)?.invalidate()

        response.sendRedirect(redirect)
    }

    private fun successRedirect(authentication: Authentication): String {
        val token = authentication as? OAuth2AuthenticationToken
            ?: throw InvalidOAuthIdentityException("Unexpected authentication type")

        // Only registrations we know about get past here, so a stray filter chain cannot
        // feed us claims from a provider whose verification rules we have not thought about.
        val provider = OAuthProvider.ofRegistrationId(token.authorizedClientRegistrationId)
            ?: throw InvalidOAuthIdentityException("Unsupported provider ${token.authorizedClientRegistrationId}")

        // An OidcUser, not a plain OAuth2User: we require an ID token, because that is the
        // artifact whose signature Spring verified. Claims scraped from a userinfo response
        // reached only by an access token are a weaker assertion.
        val oidcUser = token.principal as? OidcUser
            ?: throw InvalidOAuthIdentityException("${provider.name} did not return an OpenID Connect identity")

        val userId = oAuthAuthenticationService.authenticate(identityExtractor.extract(oidcUser))

        return UriComponentsBuilder.fromUriString(properties.successRedirectUri)
            .queryParam("code", loginCodeService.issue(userId))
            .build()
            .toUriString()
    }

    private fun errorRedirect(reason: String): String =
        UriComponentsBuilder.fromUriString(properties.failureRedirectUri)
            .queryParam("error", reason)
            .build()
            .toUriString()

    private companion object {
        const val INVALID_IDENTITY = "oauth_invalid_identity"
        const val LINKING_NOT_ALLOWED = "oauth_linking_not_allowed"
    }
}

/**
 * Provider-side failures - a denied consent screen, an invalid code, a network problem.
 * The reason is logged, never reflected into the redirect: the message can carry provider
 * detail we have no business putting in a URL.
 */
class OAuthLoginFailureHandler(
    private val properties: OAuth2Properties,
) : AuthenticationFailureHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        log.warn("OAuth sign-in failed", exception)

        response.sendRedirect(
            UriComponentsBuilder.fromUriString(properties.failureRedirectUri)
                .queryParam("error", "oauth_failed")
                .build()
                .toUriString(),
        )
    }
}
