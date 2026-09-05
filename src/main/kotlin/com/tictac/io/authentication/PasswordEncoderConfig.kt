package com.tictac.io.authentication

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration(proxyBeanMethods = false)
class PasswordEncoderConfig {

    /**
     * Argon2id via Spring Security's [Argon2PasswordEncoder], wrapped in a
     * [DelegatingPasswordEncoder] so every stored hash carries an algorithm prefix
     * (`{argon2}...`). That prefix is what lets us change parameters, or migrate off
     * Argon2 entirely, without invalidating existing passwords: old hashes keep
     * verifying against the encoder they were created with.
     *
     * `defaultsForSpringSecurity_v5_8()` is Argon2id with m=16MiB, t=2, p=1.
     * Requires BouncyCastle on the classpath.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder {
        val encoders = mapOf<String, PasswordEncoder>(
            ARGON2_ID to Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
        )
        return DelegatingPasswordEncoder(ARGON2_ID, encoders)
    }

    companion object {
        const val ARGON2_ID = "argon2"
    }
}
