package com.tictac.io.authentication.oauth

import com.tictac.io.authentication.AuthenticationService
import com.tictac.io.authentication.TokenResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class OAuthCodeExchangeRequest(
    @field:NotBlank(message = "Code is required")
    val code: String?,
)

@RestController
@RequestMapping("/api/auth/oauth")
class OAuthLoginController(
    private val loginCodeService: OAuthLoginCodeService,
    private val authenticationService: AuthenticationService,
) {

    /**
     * Exchanges the single-use code from the OAuth redirect for the same token pair a
     * password login returns. Everything downstream of this point is identical for both
     * sign-in methods - one token model, one refresh mechanism, one logout.
     */
    @PostMapping("/exchange")
    fun exchange(@Valid @RequestBody request: OAuthCodeExchangeRequest): TokenResponse =
        authenticationService.issueTokensFor(loginCodeService.consume(request.code!!))
}
