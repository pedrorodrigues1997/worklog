package com.tictac.io.authentication

import com.tictac.io.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("POST /api/auth/register")
class RegistrationApiIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `registers a user and returns only safe fields`() {
        val response = mockMvc.perform(
            post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(validBody()),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNotEmpty)
            .andExpect(jsonPath("$.firstName").value("John"))
            .andExpect(jsonPath("$.lastName").value("Smith"))
            .andExpect(jsonPath("$.email").value("john@example.com"))
            .andExpect(jsonPath("$.createdAt").isNotEmpty)
            .andReturn()
            .response
            .contentAsString

        // The credential must not come back in any shape, under any key.
        assertThat(response).doesNotContain(RAW_PASSWORD)
        assertThat(response.lowercase()).doesNotContain("password")
    }

    @Test
    fun `stores the user in postgres with an argon2id hash of the password`() {
        mockMvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(validBody()))
            .andExpect(status().isCreated)

        val stored = userRepository.findByEmail("john@example.com")
        assertThat(stored).isNotNull
        requireNotNull(stored)

        assertThat(stored.id).isNotNull
        assertThat(stored.email).isEqualTo("john@example.com")
        assertThat(stored.firstName).isEqualTo("John")
        assertThat(stored.lastName).isEqualTo("Smith")
        assertThat(stored.deletedAt).isNull()

        val hash = stored.passwordHash
        assertThat(hash).isNotNull
        requireNotNull(hash)
        assertThat(hash).isNotEqualTo(RAW_PASSWORD)
        assertThat(hash).doesNotContain(RAW_PASSWORD)
        assertThat(hash).startsWith(ARGON2_ID_PREFIX)
    }

    @Test
    fun `stored hash verifies against the raw password and rejects a wrong one`() {
        mockMvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(validBody()))
            .andExpect(status().isCreated)

        val hash = userRepository.findByEmail("john@example.com")?.passwordHash
        requireNotNull(hash)

        assertThat(passwordEncoder.matches(RAW_PASSWORD, hash)).isTrue()
        assertThat(passwordEncoder.matches("not-the-password", hash)).isFalse()
    }

    @Test
    fun `lowercases the email before storing it`() {
        mockMvc.perform(
            post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON)
                .content(validBody(email = "John@EXAMPLE.com")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value("john@example.com"))

        assertThat(userRepository.findByEmail("john@example.com")).isNotNull
    }

    @Test
    fun `rejects rather than repairs an email padded with whitespace`() {
        // Deliberate: the API is strict about the address it is given. Trimming user
        // input is the client's job, so a stray space is a visible 400 rather than a
        // silent rewrite of what the caller asked for.
        mockMvc.perform(
            post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON)
                .content(validBody(email = "  john@example.com  ")),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.email").isNotEmpty)

        assertThat(userRepository.count()).isZero()
    }

    @Test
    fun `trims surrounding whitespace from names`() {
        mockMvc.perform(
            post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON)
                .content(validBody(firstName = "  John  ", lastName = "  Smith  ")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.firstName").value("John"))
            .andExpect(jsonPath("$.lastName").value("Smith"))
    }

    @Test
    fun `rejects a duplicate email and leaves exactly one user`() {
        mockMvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(validBody()))
            .andExpect(status().isCreated)

        mockMvc.perform(
            post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON)
                .content(validBody(firstName = "Jane", lastName = "Doe")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value(409))

        assertThat(userRepository.count()).isEqualTo(1)
        assertThat(userRepository.findByEmail("john@example.com")?.firstName).isEqualTo("John")
    }

    @Test
    fun `treats emails differing only by case as duplicates`() {
        mockMvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(validBody()))
            .andExpect(status().isCreated)

        mockMvc.perform(
            post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON)
                .content(validBody(email = "JOHN@Example.COM")),
        )
            .andExpect(status().isConflict)

        assertThat(userRepository.count()).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRegistrationRequests")
    fun `rejects invalid input with a field level error and creates no user`(
        @Suppress("UNUSED_PARAMETER") case: String,
        body: String,
        expectedField: String,
    ) {
        mockMvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.errors.$expectedField").isNotEmpty)

        assertThat(userRepository.count()).isZero()
    }

    @Test
    fun `rejects a malformed json body`() {
        mockMvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content("{ not json"))
            .andExpect(status().isBadRequest)

        assertThat(userRepository.count()).isZero()
    }

    @Test
    fun `is reachable without authentication`() {
        mockMvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(validBody()))
            .andExpect(status().isCreated)
    }

    @Test
    fun `other api endpoints still require authentication`() {
        // Guards against the register rule being widened into a blanket permitAll.
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized)
        mockMvc.perform(get(REGISTER_PATH)).andExpect(status().isUnauthorized)
    }

    private companion object {
        const val REGISTER_PATH = "/api/auth/register"
        const val RAW_PASSWORD = "correct-horse-battery"
        const val ARGON2_ID_PREFIX = "{argon2}\$argon2id\$"

        fun validBody(
            firstName: String = "John",
            lastName: String = "Smith",
            email: String = "john@example.com",
            password: String = RAW_PASSWORD,
        ) = """{"firstName":"$firstName","lastName":"$lastName","email":"$email","password":"$password"}"""

        @JvmStatic
        fun invalidRegistrationRequests(): List<Arguments> = listOf(
            Arguments.of(
                "invalid email",
                """{"firstName":"John","lastName":"Smith","email":"nope","password":"$RAW_PASSWORD"}""",
                "email",
            ),
            Arguments.of(
                "null email",
                """{"firstName":"John","lastName":"Smith","email":null,"password":"$RAW_PASSWORD"}""",
                "email",
            ),
            Arguments.of(
                "missing first name",
                """{"lastName":"Smith","email":"john@example.com","password":"$RAW_PASSWORD"}""",
                "firstName",
            ),
            Arguments.of(
                "blank first name",
                """{"firstName":"   ","lastName":"Smith","email":"john@example.com","password":"$RAW_PASSWORD"}""",
                "firstName",
            ),
            Arguments.of(
                "missing last name",
                """{"firstName":"John","email":"john@example.com","password":"$RAW_PASSWORD"}""",
                "lastName",
            ),
            Arguments.of(
                "blank last name",
                """{"firstName":"John","lastName":"","email":"john@example.com","password":"$RAW_PASSWORD"}""",
                "lastName",
            ),
            Arguments.of(
                "password below minimum length",
                """{"firstName":"John","lastName":"Smith","email":"john@example.com","password":"short"}""",
                "password",
            ),
            Arguments.of(
                "missing password",
                """{"firstName":"John","lastName":"Smith","email":"john@example.com"}""",
                "password",
            ),
            Arguments.of("empty body", "{}", "email"),
        )
    }
}
