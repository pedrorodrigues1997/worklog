package com.tictac.io.authentication

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PasswordEncoderConfigTest {

    private val encoder = PasswordEncoderConfig().passwordEncoder()

    @Test
    fun `produces a prefixed argon2id hash that is not the raw password`() {
        val hash = encoder.encode(RAW_PASSWORD)

        assertThat(hash).startsWith("{argon2}\$argon2id\$")
        assertThat(hash).isNotEqualTo(RAW_PASSWORD)
        assertThat(hash).doesNotContain(RAW_PASSWORD)
    }

    @Test
    fun `verifies the correct password and rejects everything else`() {
        val hash = encoder.encode(RAW_PASSWORD)

        assertThat(encoder.matches(RAW_PASSWORD, hash)).isTrue()
        assertThat(encoder.matches("correct-horse-batter", hash)).isFalse()
        assertThat(encoder.matches("", hash)).isFalse()
        assertThat(encoder.matches(RAW_PASSWORD.uppercase(), hash)).isFalse()
    }

    @Test
    fun `salts each hash so identical passwords produce different hashes`() {
        val first = encoder.encode(RAW_PASSWORD)
        val second = encoder.encode(RAW_PASSWORD)

        assertThat(first).isNotEqualTo(second)
        assertThat(encoder.matches(RAW_PASSWORD, first)).isTrue()
        assertThat(encoder.matches(RAW_PASSWORD, second)).isTrue()
    }

    private companion object {
        const val RAW_PASSWORD = "correct-horse-battery"
    }
}
