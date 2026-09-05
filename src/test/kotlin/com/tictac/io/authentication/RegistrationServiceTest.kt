package com.tictac.io.authentication

import com.tictac.io.user.User
import com.tictac.io.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

/**
 * Isolated tests for the branches that are awkward to reach through the database:
 * the concurrent-insert race, and the guarantee that we never hash-and-store when
 * the address is already taken.
 */
class RegistrationServiceTest {

    private val userRepository: UserRepository = mock()
    private val passwordEncoder: PasswordEncoder = mock()
    private val service = RegistrationService(userRepository, passwordEncoder)

    @Test
    fun `rejects an already registered email without hashing or saving`() {
        whenever(userRepository.existsByEmail("john@example.com")).thenReturn(true)

        assertThatExceptionOfType(EmailAlreadyRegisteredException::class.java)
            .isThrownBy { service.register(request()) }

        verify(userRepository, never()).saveAndFlush(any<User>())
        verify(passwordEncoder, never()).encode(any())
    }

    @Test
    fun `maps a unique constraint violation to a duplicate email failure`() {
        // Two concurrent requests can both pass the existsByEmail check; only the
        // unique index stops the second one, and that must still surface as a 409.
        whenever(userRepository.existsByEmail(any())).thenReturn(false)
        whenever(passwordEncoder.encode(any())).thenReturn(ENCODED)
        whenever(userRepository.saveAndFlush(any<User>()))
            .thenThrow(DataIntegrityViolationException("ux_users_email"))

        assertThatExceptionOfType(EmailAlreadyRegisteredException::class.java)
            .isThrownBy { service.register(request()) }
    }

    @Test
    fun `trims names, normalises the email, and stores only the encoded password`() {
        whenever(userRepository.existsByEmail(any())).thenReturn(false)
        whenever(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED)
        whenever(userRepository.saveAndFlush(any<User>())).thenAnswer { invocation ->
            invocation.getArgument<User>(0).apply { id = UUID.randomUUID() }
        }

        val response = service.register(
            request(firstName = "  John  ", lastName = "  Smith  ", email = "  John@EXAMPLE.com  "),
        )

        val saved = argumentCaptor<User>().apply { verify(userRepository).saveAndFlush(capture()) }.firstValue
        assertThat(saved.firstName).isEqualTo("John")
        assertThat(saved.lastName).isEqualTo("Smith")
        assertThat(saved.email).isEqualTo("john@example.com")
        assertThat(saved.passwordHash).isEqualTo(ENCODED)
        assertThat(saved.passwordHash).isNotEqualTo(RAW_PASSWORD)

        assertThat(response.email).isEqualTo("john@example.com")
        assertThat(response.firstName).isEqualTo("John")
        assertThat(response.id).isEqualTo(saved.id)
    }

    @Test
    fun `entity toString never exposes the password hash`() {
        // Entities end up in log lines and exception messages; this is how hashes leak.
        val user = User(firstName = "John", lastName = "Smith", email = "john@example.com", passwordHash = ENCODED)

        assertThat(user.toString()).doesNotContain(ENCODED)
        assertThat(user.toString()).contains("john@example.com")
    }

    private fun request(
        firstName: String = "John",
        lastName: String = "Smith",
        email: String = "john@example.com",
        password: String = RAW_PASSWORD,
    ) = RegisterRequest(firstName = firstName, lastName = lastName, email = email, password = password)

    private companion object {
        const val RAW_PASSWORD = "correct-horse-battery"
        const val ENCODED = "{argon2}\$argon2id\$v=19\$m=16384,t=2,p=1\$salt\$hash"
    }
}
