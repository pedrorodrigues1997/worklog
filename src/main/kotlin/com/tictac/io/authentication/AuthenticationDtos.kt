package com.tictac.io.authentication

import jakarta.validation.constraints.NotBlank

/**
 * Login carries no format constraints beyond "present". Rejecting a badly formed address
 * with a different status than a wrong password would tell an attacker something about
 * the input; more importantly, format rules here only ever lock out legitimate users with
 * unusual but valid addresses.
 */
data class LoginRequest(
    @field:NotBlank(message = "Email is required")
    val email: String?,

    @field:NotBlank(message = "Password is required")
    val password: String?,
)

data class RefreshRequest(
    @field:NotBlank(message = "Refresh token is required")
    val refreshToken: String?,
)

data class LogoutRequest(
    @field:NotBlank(message = "Refresh token is required")
    val refreshToken: String?,
)

/**
 * Shaped like an OAuth2 token response so clients can reuse familiar handling.
 * [expiresIn] is seconds until the access token expires, letting a client refresh
 * proactively instead of waiting for a 401.
 */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Long,
)
