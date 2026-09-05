package com.tictac.io.authentication

import com.tictac.io.user.User
import com.tictac.io.user.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

@Service
class RegistrationService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    @Transactional
    fun register(request: RegisterRequest): RegisteredUserResponse {
        // Non-null assertions are safe: the controller validates the request with
        // @Valid, so these fields cannot be null or blank by the time we get here.
        val email = normalizeEmail(request.email!!)

        // Cheap pre-check so the common case returns a clean 409 rather than surfacing
        // a database constraint error. It is not the actual guarantee - the unique
        // index is - because two concurrent requests can both pass this check.
        if (userRepository.existsByEmail(email)) {
            throw EmailAlreadyRegisteredException()
        }

        val user = User(
            firstName = request.firstName!!.trim(),
            lastName = request.lastName!!.trim(),
            email = email,
            passwordHash = passwordEncoder.encode(request.password!!),
        )

        val saved = try {
            // saveAndFlush, not save: we need the INSERT to hit the database inside
            // this try block so the unique-index violation is catchable here rather
            // than at transaction commit, outside the method.
            userRepository.saveAndFlush(user)
        } catch (ex: DataIntegrityViolationException) {
            // Lost the race against a concurrent registration of the same address.
            throw EmailAlreadyRegisteredException()
        }

        return RegisteredUserResponse(
            id = saved.id!!,
            firstName = saved.firstName,
            lastName = saved.lastName,
            email = saved.email,
            createdAt = saved.createdAt,
        )
    }

    /**
     * Local parts are case-sensitive per RFC 5321, but no mail provider in practice
     * treats them that way, and users expect Foo@example.com and foo@example.com to be
     * the same account. Normalising on write keeps the unique index sufficient.
     */
    private fun normalizeEmail(email: String): String = email.trim().lowercase(Locale.ROOT)
}
