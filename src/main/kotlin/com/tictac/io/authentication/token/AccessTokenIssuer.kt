package com.tictac.io.authentication.token

import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** An access token and the moment it stops being valid. */
data class IssuedAccessToken(val value: String, val expiresAt: Instant)

@Component
class AccessTokenIssuer(
    private val jwtEncoder: JwtEncoder,
    private val properties: JwtProperties,
) {

    /**
     * Claims are deliberately minimal: issuer, subject (the user id), timestamps and a
     * token id. Nothing else. A JWT is signed but not encrypted - anyone holding it can
     * read every claim - so names, email addresses and roles stay out of it. The user id
     * alone is enough for the server to look up whatever it needs.
     */
    fun issue(userId: UUID): IssuedAccessToken {
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(properties.accessTokenTtl)

        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .subject(userId.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .build()

        val header = JwsHeader.with(JwtConfig.ALGORITHM).build()
        val value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue

        return IssuedAccessToken(value, expiresAt)
    }
}
