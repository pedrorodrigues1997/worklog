package com.tictac.io.authentication

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Fields are nullable on purpose. With non-null Kotlin types, a missing JSON property
 * fails during deserialisation and produces an opaque Jackson error; nullable fields
 * let Bean Validation report a proper per-field message instead.
 */
data class RegisterRequest(
    @field:NotBlank(message = "First name is required")
    @field:Size(max = 100, message = "First name must be at most 100 characters")
    val firstName: String?,

    @field:NotBlank(message = "Last name is required")
    @field:Size(max = 100, message = "Last name must be at most 100 characters")
    val lastName: String?,

    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Email must be a valid email address")
    @field:Size(max = 320, message = "Email must be at most 320 characters")
    val email: String?,

    @field:NotBlank(message = "Password is required")
    @field:Size(
        min = MIN_PASSWORD_LENGTH,
        max = MAX_PASSWORD_LENGTH,
        message = "Password must be between $MIN_PASSWORD_LENGTH and $MAX_PASSWORD_LENGTH characters",
    )
    val password: String?,
) {
    companion object {
        const val MIN_PASSWORD_LENGTH = 12

        /** Bounded so a caller cannot hand the hashing function an arbitrarily large input. */
        const val MAX_PASSWORD_LENGTH = 128
    }
}

/** Safe projection of a user. Must never gain a password or password-hash field. */
data class RegisteredUserResponse(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val email: String,
    val createdAt: Instant,
)
