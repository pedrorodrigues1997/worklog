package com.tictac.io.common.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import java.util.UUID

class CurrentUserTest {

    private val currentUser = CurrentUser()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `reads the user id from the token subject`() {
        val userId = UUID.randomUUID()
        authenticateWithJwt(subject = userId.toString())

        assertThat(currentUser.idOrNull()).isEqualTo(userId)
        assertThat(currentUser.id()).isEqualTo(userId)
    }

    @Test
    fun `has no user when the context is empty`() {
        assertThat(currentUser.idOrNull()).isNull()
        assertThatExceptionOfType(AccessDeniedException::class.java).isThrownBy { currentUser.id() }
    }

    @Test
    fun `treats an anonymous authentication as no user`() {
        SecurityContextHolder.getContext().authentication = AnonymousAuthenticationToken(
            "key",
            "anonymousUser",
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"),
        )

        assertThat(currentUser.idOrNull()).isNull()
    }

    @Test
    fun `ignores a principal that did not come from a verified token`() {
        // Nothing but a signature-verified JWT counts as identity here.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null, emptyList())

        assertThat(currentUser.idOrNull()).isNull()
    }

    @Test
    fun `ignores a token whose subject is not a uuid`() {
        authenticateWithJwt(subject = "john@example.com")

        assertThat(currentUser.idOrNull()).isNull()
    }

    private fun authenticateWithJwt(subject: String) {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900))
            .build()

        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt, emptyList())
    }
}
