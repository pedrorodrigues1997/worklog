package com.tictac.io.common

import com.tictac.io.authentication.EmailAlreadyRegisteredException
import com.tictac.io.authentication.InvalidCredentialsException
import com.tictac.io.authentication.token.InvalidRefreshTokenException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Error responses use RFC 9457 `application/problem+json` ([ProblemDetail]).
 *
 * Extending [ResponseEntityExceptionHandler] means the framework's own exceptions
 * (malformed JSON, wrong method, unsupported media type, ...) keep their correct status
 * codes and safe generic messages; we only add the cases Spring cannot classify. It also
 * keeps the catch-all below from swallowing them, since a more specific handler in the
 * same advice always wins.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Bean Validation failures, reported per field so a form can highlight them. */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed")
        problem.title = "Validation failed"
        problem.setProperty(
            "errors",
            ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "is invalid") },
        )
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request)
    }

    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    fun handleEmailAlreadyRegistered(ex: EmailAlreadyRegisteredException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Email already registered"
        }

    /**
     * Login failure. Deliberately identical whether the address is unknown, the password
     * is wrong, or the account is deleted - the response must not help enumerate accounts.
     */
    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password").apply {
            title = "Authentication failed"
        }

    /** Unknown, expired, already-rotated or revoked refresh token - all indistinguishable. */
    @ExceptionHandler(InvalidRefreshTokenException::class)
    fun handleInvalidRefreshToken(ex: InvalidRefreshTokenException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or has expired").apply {
            title = "Authentication failed"
        }

    /**
     * Declared explicitly so the catch-all below cannot turn an authorisation failure
     * into a 500. This becomes load-bearing once tenant-scoped authorisation lands.
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied").apply {
            title = "Forbidden"
        }

    /** Last resort. Log the cause for us, tell the client nothing about it. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error("Unhandled exception while processing request", ex)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred",
        ).apply { title = "Internal server error" }
    }
}
