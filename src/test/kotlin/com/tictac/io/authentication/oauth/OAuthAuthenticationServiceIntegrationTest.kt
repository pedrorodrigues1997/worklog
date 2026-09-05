package com.tictac.io.authentication.oauth

import com.tictac.io.support.PostgresIntegrationTest
import com.tictac.io.user.User
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.UUID

/**
 * The linking rules, exercised against a real database.
 *
 * The provider is stubbed at exactly the right boundary: [OAuthUserIdentity] is what is
 * left after Spring Security has verified an ID token's signature, audience and expiry, so
 * tests start from verified claims and never need Google to be reachable.
 */
@DisplayName("OAuth identity resolution")
class OAuthAuthenticationServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var service: OAuthAuthenticationService

    @Test
    fun `creates an internal user on first sign-in`() {
        val userId = service.authenticate(identity())

        val user = userRepository.findById(userId).orElseThrow()
        assertThat(user.email).isEqualTo("john@example.com")
        assertThat(user.firstName).isEqualTo("John")
        assertThat(user.lastName).isEqualTo("Smith")
        // No password until the owner sets one; password login already refuses a null hash.
        assertThat(user.passwordHash).isNull()

        val identity = userIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1")
        assertThat(identity).isNotNull
        assertThat(identity!!.userId).isEqualTo(userId)
    }

    @Test
    fun `the internal id is ours, not the providers`() {
        val userId = service.authenticate(identity(subject = "google-sub-1"))

        // The subject is a lookup key into user_identities and nothing more.
        assertThat(userId.toString()).isNotEqualTo("google-sub-1")
        assertThat(userRepository.findById(userId)).isPresent
        assertThat(userIdentityRepository.findAllByUserId(userId).single().providerUserId).isEqualTo("google-sub-1")
    }

    @Test
    fun `returns the same user on every later sign-in`() {
        val first = service.authenticate(identity())
        val second = service.authenticate(identity())

        assertThat(second).isEqualTo(first)
        assertThat(userRepository.count()).isEqualTo(1)
        assertThat(userIdentityRepository.count()).isEqualTo(1)
    }

    @Test
    fun `matches on the subject, not the email, so a changed address keeps the account`() {
        val userId = service.authenticate(identity(email = "john@example.com"))

        val afterRename = service.authenticate(identity(email = "john.smith@example.com"))

        assertThat(afterRename).isEqualTo(userId)
        assertThat(userRepository.count()).isEqualTo(1)
        // The address on our record is the one we already had; the provider does not get to
        // rewrite it as a side effect of signing in.
        assertThat(userRepository.findById(userId).orElseThrow().email).isEqualTo("john@example.com")
    }

    @Test
    fun `links to an existing password account with the same verified email`() {
        val existing = persistUser(email = "john@example.com", passwordHash = "{argon2}stub")

        val userId = service.authenticate(identity(email = "john@example.com"))

        assertThat(userId).isEqualTo(existing)
        assertThat(userRepository.count()).isEqualTo(1)
        assertThat(userIdentityRepository.findAllByUserId(existing)).hasSize(1)
        // The password survives - the account now has two ways in, not a replaced one.
        assertThat(userRepository.findById(existing).orElseThrow().passwordHash).isEqualTo("{argon2}stub")
    }

    @Test
    fun `matches the existing account case insensitively`() {
        val existing = persistUser(email = "john@example.com")

        assertThat(service.authenticate(identity(email = "John@EXAMPLE.com"))).isEqualTo(existing)
        assertThat(userRepository.count()).isEqualTo(1)
    }

    @Test
    fun `refuses to link an unverified email to an existing account`() {
        // The account-takeover case: anyone able to type a victim's address into a provider
        // signup form must not thereby inherit the victim's account.
        val existing = persistUser(email = "john@example.com", passwordHash = "{argon2}stub")

        assertThatExceptionOfType(OAuthLinkingNotAllowedException::class.java)
            .isThrownBy { service.authenticate(identity(emailVerified = false)) }

        assertThat(userIdentityRepository.findAllByUserId(existing)).isEmpty()
    }

    @Test
    fun `refuses to create an account from an unverified email`() {
        // ...and the squatting case: claiming an address you do not own would lock out the
        // person who does.
        assertThatExceptionOfType(OAuthLinkingNotAllowedException::class.java)
            .isThrownBy { service.authenticate(identity(emailVerified = false)) }

        assertThat(userRepository.count()).isZero()
    }

    @Test
    fun `keeps working for an already linked identity even if the email is now unverified`() {
        val userId = service.authenticate(identity())

        // The link was established under a verified address; sign-in now goes by subject
        // and never consults the email again.
        assertThat(service.authenticate(identity(emailVerified = false))).isEqualTo(userId)
    }

    @Test
    fun `refuses a second identity from the same provider on one account`() {
        val userId = service.authenticate(identity(subject = "google-sub-1"))

        // A different Google account whose address happens to match: ambiguous, so refuse
        // rather than guess.
        assertThatExceptionOfType(OAuthLinkingNotAllowedException::class.java)
            .isThrownBy { service.authenticate(identity(subject = "google-sub-2")) }

        assertThat(userIdentityRepository.findAllByUserId(userId)).hasSize(1)
    }

    @Test
    fun `refuses an identity whose account has been closed`() {
        val userId = service.authenticate(identity())
        userRepository.findById(userId).orElseThrow()
            .also { it.deletedAt = Instant.now() }
            .let { userRepository.saveAndFlush(it) }

        assertThatExceptionOfType(OAuthLinkingNotAllowedException::class.java)
            .isThrownBy { service.authenticate(identity()) }
    }

    @Test
    fun `refuses to link to a closed account with the same email`() {
        persistUser(email = "john@example.com", deletedAt = Instant.now())

        assertThatExceptionOfType(OAuthLinkingNotAllowedException::class.java)
            .isThrownBy { service.authenticate(identity()) }
    }

    @Test
    fun `the database refuses the same provider identity on two accounts`() {
        val first = service.authenticate(identity(subject = "google-sub-1"))
        val other = persistUser(email = "someone-else@example.com")

        // The unique index is the real guarantee behind the service-level checks.
        assertThatExceptionOfType(DataIntegrityViolationException::class.java).isThrownBy {
            userIdentityRepository.saveAndFlush(
                UserIdentity(userId = other, provider = OAuthProvider.GOOGLE, providerUserId = "google-sub-1"),
            )
        }
        assertThat(userIdentityRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1")!!.userId)
            .isEqualTo(first)
    }

    private fun identity(
        provider: OAuthProvider = OAuthProvider.GOOGLE,
        subject: String = "google-sub-1",
        email: String = "john@example.com",
        emailVerified: Boolean = true,
        firstName: String = "John",
        lastName: String = "Smith",
    ) = OAuthUserIdentity(provider, subject, email, emailVerified, firstName, lastName)

    private fun persistUser(
        email: String,
        passwordHash: String? = null,
        deletedAt: Instant? = null,
    ): UUID =
        userRepository.saveAndFlush(
            User(firstName = "John", lastName = "Smith", email = email, passwordHash = passwordHash)
                .also { it.deletedAt = deletedAt },
        ).id!!
}
