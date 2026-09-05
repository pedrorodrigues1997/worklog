package com.tictac.io.authentication.token

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = "security.jwt")
data class JwtProperties(

    /** Signing key for HS256. Supplied by the environment; never defaulted. */
    val secret: String,

    /**
     * Value of the `iss` claim, and the issuer every incoming token is checked against.
     * Required, with no default: a placeholder baked into the build would be wrong in
     * every deployment and silently accepted by all of them.
     */
    val issuer: String,

    /** Deliberately short: a stolen access token stays useful only for this long. */
    val accessTokenTtl: Duration,

    val refreshTokenTtl: Duration,

    /**
     * Tolerance for clock drift between this server and whatever issued the token -
     * which is this same server, so it can be small. Spring's 60-second default is sized
     * for federated issuers and would extend the life of every access token by a minute.
     */
    val clockSkew: Duration,
) {
    init {
        // Fail at startup rather than silently signing with a weak key. HS256 needs at
        // least 256 bits; below that Nimbus refuses the key anyway, but a clear message
        // here beats a stack trace on the first login.
        require(secret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "security.jwt.secret must be at least $MIN_SECRET_BYTES bytes " +
                "(generate one with: openssl rand -base64 48)"
        }
        // An absolute URI, so Spring's Jwt.getIssuer() works and the value is unambiguous
        // across environments. "io-backend" would parse but tell nobody which deployment
        // minted the token.
        require(issuer.isNotBlank() && runCatching { URI(issuer).isAbsolute }.getOrDefault(false)) {
            "security.jwt.issuer must be an absolute URI identifying this deployment, " +
                "e.g. https://api.example.com"
        }
        require(!accessTokenTtl.isNegative && !accessTokenTtl.isZero) {
            "security.jwt.access-token-ttl must be positive"
        }
        require(!refreshTokenTtl.isNegative && !refreshTokenTtl.isZero) {
            "security.jwt.refresh-token-ttl must be positive"
        }
        require(!clockSkew.isNegative) { "security.jwt.clock-skew must not be negative" }
    }

    companion object {
        const val MIN_SECRET_BYTES = 32
    }
}
