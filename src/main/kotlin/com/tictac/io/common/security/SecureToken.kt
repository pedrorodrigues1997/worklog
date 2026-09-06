package com.tictac.io.common.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * Opaque bearer secrets: refresh tokens, OAuth handoff codes, invitation tokens.
 *
 * All three follow the same recipe, and it was written out three times before this existed.
 * The recipe is not the interesting part - the reasons behind it are, and they are the same
 * every time:
 *
 * - **[SecureRandom], not [java.util.Random].** These values are the entire credential; a
 *   predictable one is no credential at all.
 * - **256 bits.** Far beyond guessing range, so nothing downstream needs to rate-limit
 *   guesses at the token itself.
 * - **URL-safe Base64, unpadded.** These travel in redirect URLs and invitation links, where
 *   `+`, `/` and `=` would need escaping that something, somewhere, would get wrong.
 * - **SHA-256 at rest, never the raw value.** A database dump then contains no usable
 *   tokens. SHA-256 rather than Argon2 is deliberate: there is no low-entropy secret to
 *   brute-force here, and a slow hash would make every refresh and every acceptance
 *   expensive for no gain.
 *
 * Deliberately not a Spring bean: it holds one thread-safe [SecureRandom] and no state worth
 * injecting, and making it a bean would invite someone to mock it.
 */
object SecureToken {

    /** 256 bits from a CSPRNG. */
    const val TOKEN_BYTES = 32

    const val HASH_ALGORITHM = "SHA-256"

    /** Hex-encoded SHA-256 is exactly this wide, which is what the `varchar(64)` columns hold. */
    const val HASH_LENGTH = 64

    private val secureRandom = SecureRandom()

    /**
     * A fresh token. This is the only moment the raw value exists on the server, and callers
     * must hand it straight to whoever it is for - never store it, never log it.
     */
    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** The stored form. Deterministic, so a presented token can be looked up by its hash. */
    fun hash(rawToken: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance(HASH_ALGORITHM).digest(rawToken.toByteArray(Charsets.UTF_8)),
        )
}
