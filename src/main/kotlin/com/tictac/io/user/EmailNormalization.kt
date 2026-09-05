package com.tictac.io.user

import java.util.Locale

/**
 * Shared by registration and login: both sides must agree on the exact string, otherwise
 * an account created as Foo@example.com could never be logged into as foo@example.com.
 *
 * Local parts are case-sensitive per RFC 5321, but no mail provider in practice treats
 * them that way and users do not expect it either.
 */
fun normalizeEmail(email: String): String = email.trim().lowercase(Locale.ROOT)
