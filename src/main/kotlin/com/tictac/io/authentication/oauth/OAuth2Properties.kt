package com.tictac.io.authentication.oauth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = "security.oauth2")
data class OAuth2Properties(

    /**
     * Where the browser is sent after a provider sign-in, with `?code=` appended.
     *
     * Taken from configuration and never from the request. A caller-supplied return address
     * is how OAuth flows turn into open redirects.
     */
    val successRedirectUri: String,

    /** Where the browser is sent when sign-in fails, with `?error=` appended. */
    val failureRedirectUri: String,

    /** Seconds, not minutes: the code only has to survive one browser redirect. */
    val loginCodeTtl: Duration,

    val google: GoogleCredentials,
) {
    /** Blank client ids mean "not configured", which is the normal state locally. */
    data class GoogleCredentials(val clientId: String, val clientSecret: String) {
        val configured: Boolean get() = clientId.isNotBlank()
    }

    init {
        require(isAbsolute(successRedirectUri)) {
            "security.oauth2.success-redirect-uri must be an absolute URI"
        }
        require(isAbsolute(failureRedirectUri)) {
            "security.oauth2.failure-redirect-uri must be an absolute URI"
        }
        require(!loginCodeTtl.isNegative && !loginCodeTtl.isZero) {
            "security.oauth2.login-code-ttl must be positive"
        }
        require(!google.configured || google.clientSecret.isNotBlank()) {
            "security.oauth2.google.client-secret is required when a client id is set"
        }
    }

    private fun isAbsolute(value: String) =
        value.isNotBlank() && runCatching { URI(value).isAbsolute }.getOrDefault(false)
}
