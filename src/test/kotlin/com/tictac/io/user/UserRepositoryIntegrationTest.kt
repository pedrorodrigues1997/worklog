package com.tictac.io.user

import com.tictac.io.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant

class UserRepositoryIntegrationTest : PostgresIntegrationTest() {

    @Test
    fun `assigns an id and created timestamp on insert`() {
        val before = Instant.now()

        val saved = userRepository.saveAndFlush(newUser())

        assertThat(saved.id).isNotNull
        assertThat(saved.createdAt).isAfterOrEqualTo(before.minusSeconds(1))
        assertThat(saved.deletedAt).isNull()
    }

    @Test
    fun `finds a user by email`() {
        userRepository.saveAndFlush(newUser())

        assertThat(userRepository.findByEmail("john@example.com")).isNotNull
        assertThat(userRepository.existsByEmail("john@example.com")).isTrue()
        assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse()
    }

    @Test
    fun `the unique index rejects a duplicate email`() {
        userRepository.saveAndFlush(newUser())

        assertThatExceptionOfType(DataIntegrityViolationException::class.java)
            .isThrownBy { userRepository.saveAndFlush(newUser(firstName = "Jane")) }
    }

    @Test
    fun `lookups are exact, so normalisation is the applications responsibility`() {
        // Documents why RegistrationService lowercases before writing: the index is
        // byte-exact, it does not fold case for us.
        userRepository.saveAndFlush(newUser())

        assertThat(userRepository.existsByEmail("JOHN@EXAMPLE.COM")).isFalse()
    }

    @Test
    fun `allows a user with no password hash`() {
        // Reserved for identity-provider accounts added later.
        val saved = userRepository.saveAndFlush(newUser(passwordHash = null))

        assertThat(saved.passwordHash).isNull()
    }

    private fun newUser(
        firstName: String = "John",
        lastName: String = "Smith",
        email: String = "john@example.com",
        passwordHash: String? = "{argon2}\$argon2id\$stub",
    ) = User(firstName = firstName, lastName = lastName, email = email, passwordHash = passwordHash)
}
