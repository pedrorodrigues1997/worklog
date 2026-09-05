package com.tictac.io.common.security

import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The single source of truth for "who is making this request".
 *
 * The identity comes from the `sub` claim of a signature-verified JWT held in the security
 * context - never from a path variable, request body or header. Any code that needs the
 * caller's id injects this rather than accepting a user id as a parameter from the client,
 * which is what keeps a request from acting as someone else by editing its own payload.
 */
@Component
class CurrentUser {

    fun idOrNull(): UUID? {
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        if (!authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) return null

        val jwt = authentication.principal as? Jwt ?: return null
        return runCatching { UUID.fromString(jwt.subject) }.getOrNull()
    }

    /** For code paths that are only reachable behind authentication. */
    fun id(): UUID = idOrNull() ?: throw AccessDeniedException("No authenticated user in the security context")
}
