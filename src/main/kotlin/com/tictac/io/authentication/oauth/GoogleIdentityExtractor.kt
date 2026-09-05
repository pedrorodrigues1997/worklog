package com.tictac.io.authentication.oauth

import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component

/**
 * Turns Google's verified OIDC claims into our [OAuthUserIdentity].
 *
 * Deliberately a concrete class rather than a per-provider strategy: there is one provider.
 * A second one would need its own extractor, because the claim that answers "is this address
 * verified?" is not the same everywhere - and getting that wrong is an account-takeover bug,
 * not a formatting inconvenience. That is the point at which an interface earns its place.
 */
@Component
class GoogleIdentityExtractor {

    fun extract(user: OidcUser): OAuthUserIdentity {
        val subject = user.subject?.takeIf { it.isNotBlank() }
            ?: throw InvalidOAuthIdentityException("Google did not return a subject identifier")

        val email = user.email?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidOAuthIdentityException("Google did not return an email address")

        val (firstName, lastName) = namesOf(user, email)

        return OAuthUserIdentity(
            provider = OAuthProvider.GOOGLE,
            subject = subject,
            email = email,
            // Google always returns this claim, so the rule is simply to believe it. Absent
            // means unverified: never assume verification we were not told about.
            emailVerified = user.getClaimAsBoolean("email_verified") ?: false,
            firstName = firstName,
            lastName = lastName,
        )
    }

    /**
     * `users.first_name` and `last_name` are NOT NULL, but a provider may return neither, so
     * fall back through `name` and finally to the local part of the address rather than
     * failing a sign-in over a missing display name.
     */
    private fun namesOf(user: OidcUser, email: String): Pair<String, String> {
        val given = user.givenName?.trim().orEmpty()
        val family = user.familyName?.trim().orEmpty()
        if (given.isNotEmpty()) return given to family

        val fullName = user.fullName?.trim().orEmpty()
        if (fullName.isNotEmpty()) {
            val parts = fullName.split(" ").filter { it.isNotBlank() }
            return parts.first() to parts.drop(1).joinToString(" ")
        }

        return email.substringBefore('@') to ""
    }
}
