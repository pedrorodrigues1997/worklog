package com.tictac.io.authentication.oauth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import java.time.Instant

/**
 * Claim mapping. These build a real [OidcUser] from claims - the same object Spring hands us
 * once it has verified the ID token - so nothing here touches the network.
 */
class GoogleIdentityExtractorTest {

    private val google = GoogleIdentityExtractor()

    @Test
    fun `google trusts its own email_verified claim`() {
        val verified = google.extract(
            oidcUser(mapOf("sub" to "g-1", "email" to "john@example.com", "email_verified" to true)),
        )
        assertThat(verified.emailVerified).isTrue()
        assertThat(verified.subject).isEqualTo("g-1")
        assertThat(verified.provider).isEqualTo(OAuthProvider.GOOGLE)

        val unverified = google.extract(
            oidcUser(mapOf("sub" to "g-1", "email" to "john@example.com", "email_verified" to false)),
        )
        assertThat(unverified.emailVerified).isFalse()
    }

    @Test
    fun `google treats a missing email_verified claim as unverified`() {
        val identity = google.extract(oidcUser(mapOf("sub" to "g-1", "email" to "john@example.com")))

        assertThat(identity.emailVerified).isFalse()
    }

    @Test
    fun `rejects a response with no subject`() {
        assertThatExceptionOfType(InvalidOAuthIdentityException::class.java)
            .isThrownBy { google.extract(oidcUser(mapOf("sub" to "", "email" to "john@example.com"))) }
            .withMessageContaining("subject")
    }

    @Test
    fun `rejects a response with no usable email`() {
        listOf(null, "", "   ").forEach { email ->
            val claims = buildMap<String, Any> {
                put("sub", "g-1")
                email?.let { put("email", it) }
            }
            assertThatExceptionOfType(InvalidOAuthIdentityException::class.java)
                .isThrownBy { google.extract(oidcUser(claims)) }
                .withMessageContaining("email")
        }
    }

    @Test
    fun `falls back through name and then the local part for a display name`() {
        val fromGivenName = google.extract(
            oidcUser(
                mapOf(
                    "sub" to "g-1", "email" to "j@example.com", "email_verified" to true,
                    "given_name" to "John", "family_name" to "Smith",
                ),
            ),
        )
        assertThat(fromGivenName.firstName to fromGivenName.lastName).isEqualTo("John" to "Smith")

        val fromFullName = google.extract(
            oidcUser(mapOf("sub" to "g-1", "email" to "j@example.com", "name" to "Ada Lovelace King")),
        )
        assertThat(fromFullName.firstName to fromFullName.lastName).isEqualTo("Ada" to "Lovelace King")

        // users.first_name is NOT NULL, so a provider that returns no name at all must not
        // be able to fail a sign-in.
        val fromEmail = google.extract(oidcUser(mapOf("sub" to "g-1", "email" to "ada@example.com")))
        assertThat(fromEmail.firstName).isEqualTo("ada")
    }

    private fun oidcUser(claims: Map<String, Any>): OidcUser {
        val idToken = OidcIdToken(
            "id-token-value",
            Instant.now(),
            Instant.now().plusSeconds(300),
            claims + mapOf("iss" to "https://issuer.test.invalid", "aud" to listOf("client-id")),
        )
        return DefaultOidcUser(emptyList(), idToken, "sub")
    }
}
