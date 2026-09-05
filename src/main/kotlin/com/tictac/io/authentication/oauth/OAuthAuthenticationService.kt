package com.tictac.io.authentication.oauth

import com.tictac.io.user.User
import com.tictac.io.user.UserRepository
import com.tictac.io.user.normalizeEmail
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Turns a verified external identity into one of our internal user ids.
 *
 * This is the only place OAuth touches the user model, and it deliberately ends at a UUID:
 * everything downstream - token issuing, authorisation, every future feature - sees the
 * same `users.id` whether the person signed in with a password or with Google.
 */
@Service
class OAuthAuthenticationService(
    private val userRepository: UserRepository,
    private val userIdentityRepository: UserIdentityRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun authenticate(identity: OAuthUserIdentity): UUID {
        // The provider's subject is the identity. Email is only ever used to *discover* an
        // account to link to on first sign-in; after that this lookup answers on its own,
        // so a user who changes their Google address keeps their account.
        userIdentityRepository.findByProviderAndProviderUserId(identity.provider, identity.subject)
            ?.let { return activeUserIdOf(it.userId) }

        return try {
            linkNewIdentity(identity)
        } catch (ex: DataIntegrityViolationException) {
            // Two sign-ins for the same brand-new identity raced. One of them won and the
            // row now exists, so re-read rather than failing the loser's login.
            userIdentityRepository.findByProviderAndProviderUserId(identity.provider, identity.subject)
                ?.let { activeUserIdOf(it.userId) }
                ?: throw ex
        }
    }

    private fun linkNewIdentity(identity: OAuthUserIdentity): UUID {
        // Refusing here is the account-takeover defence. Attaching an unverified address to
        // an existing account would let anyone who can type that address into a provider's
        // signup form inherit the account; creating a *new* account on one lets them squat
        // an address they do not own and lock out its owner. Note this gate applies only to
        // establishing the link - once it exists, sign-in goes through the subject lookup
        // above and never consults the email again.
        if (!identity.emailVerified) {
            throw OAuthLinkingNotAllowedException(
                "${identity.provider.name} did not verify this email address",
            )
        }

        val email = normalizeEmail(identity.email)
        val existing = userRepository.findByEmail(email)

        val user = when {
            existing == null -> createUser(identity, email)

            existing.deletedAt != null ->
                throw OAuthLinkingNotAllowedException("The account for this email address is closed")

            // Same account, second identity from the same provider: a different Google
            // account whose address happens to match ours. Ambiguous, so refuse rather than
            // guess which one the user meant.
            userIdentityRepository.findByUserIdAndProvider(existing.id!!, identity.provider) != null ->
                throw OAuthLinkingNotAllowedException(
                    "This account already has a different ${identity.provider.name} identity linked",
                )

            else -> existing
        }

        userIdentityRepository.saveAndFlush(
            UserIdentity(
                userId = user.id!!,
                provider = identity.provider,
                providerUserId = identity.subject,
            ),
        )

        log.info("Linked {} identity to user {}", identity.provider, user.id)
        return user.id!!
    }

    /**
     * OAuth-created accounts get no password hash. Password login already treats a null
     * hash as a failed sign-in, so such an account cannot be entered with a password until
     * its owner sets one.
     */
    private fun createUser(identity: OAuthUserIdentity, email: String): User =
        userRepository.saveAndFlush(
            User(
                firstName = identity.firstName,
                lastName = identity.lastName,
                email = email,
                passwordHash = null,
            ),
        )

    private fun activeUserIdOf(userId: UUID): UUID {
        val user = userRepository.findById(userId)
            .orElseThrow { OAuthLinkingNotAllowedException("The account for this identity no longer exists") }

        if (user.deletedAt != null) {
            throw OAuthLinkingNotAllowedException("The account for this identity is closed")
        }
        return userId
    }
}
