package com.tictac.io.authentication.token

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * HS256 with a shared secret. A single monolith signs and verifies its own tokens, so
 * asymmetric keys would buy nothing: there is no third party that needs to verify a
 * token without also being able to mint one. Revisit if another service ever needs to
 * validate our tokens.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties::class)
class JwtConfig(private val properties: JwtProperties) {

    private fun signingKey(): SecretKey =
        SecretKeySpec(properties.secret.toByteArray(Charsets.UTF_8), KEY_ALGORITHM)

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(signingKey()))

    @Bean
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(signingKey())
            .macAlgorithm(ALGORITHM)
            .build()
            .apply {
                // Checks exp/nbf *and* pins the issuer, so a token signed with our key
                // for some other purpose cannot be replayed against this API. Built by
                // hand rather than via JwtValidators.createDefaultWithIssuer so the clock
                // skew is ours to choose instead of the 60-second default.
                setJwtValidator(
                    DelegatingOAuth2TokenValidator<Jwt>(
                        JwtTimestampValidator(properties.clockSkew),
                        JwtIssuerValidator(properties.issuer),
                    ),
                )
            }

    companion object {
        val ALGORITHM: MacAlgorithm = MacAlgorithm.HS256
        const val KEY_ALGORITHM = "HmacSHA256"
    }
}
