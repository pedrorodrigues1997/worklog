package com.tictac.io.authentication.oauth

/**
 * Providers we accept. The value is also the Spring Security registration id.
 *
 * One entry today. The enum, the `provider` column and the unique index on
 * (provider, provider_user_id) all stay provider-shaped so a second one is a new constant
 * and an extractor rather than a schema change.
 */
enum class OAuthProvider(val registrationId: String) {
    GOOGLE("google"),
    ;

    companion object {
        fun ofRegistrationId(registrationId: String): OAuthProvider? =
            entries.firstOrNull { it.registrationId == registrationId }
    }
}

/**
 * What a provider asserted about the person who just signed in, after Spring Security has
 * verified the ID token's signature, audience and expiry.
 *
 * Every field here comes from that verified token. Nothing on this object is ever taken
 * from a request parameter or body - that is the whole point of the type existing.
 */
data class OAuthUserIdentity(
    val provider: OAuthProvider,

    /** The provider's `sub`. Stable for our application, and the key we actually trust. */
    val subject: String,

    val email: String,

    /**
     * Whether the provider vouches that this person controls [email].
     *
     * Load-bearing: it is the difference between "sign in to the existing account with this
     * address" and "let anyone who can type an address into a signup form take it over".
     */
    val emailVerified: Boolean,

    val firstName: String,
    val lastName: String,
)

/** The provider's response was unusable - missing subject, missing email, and so on. */
class InvalidOAuthIdentityException(message: String) : RuntimeException(message)

/** The identity is valid, but attaching it to an internal account is not allowed. */
class OAuthLinkingNotAllowedException(message: String) : RuntimeException(message)
