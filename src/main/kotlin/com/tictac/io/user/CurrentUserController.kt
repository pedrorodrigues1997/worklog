package com.tictac.io.user

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class UserProfileResponse(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val email: String,
    val createdAt: Instant,
)

@RestController
@RequestMapping("/api/users")
class CurrentUserController(
    private val activeUser: ActiveUser,
    private val accountClosureService: AccountClosureService,
) {

    /**
     * Requires authentication. Note there is no `/api/users/{id}` variant returning
     * someone else's profile - the caller is identified by their token, so there is no
     * id for a request to tamper with.
     */
    @GetMapping("/me")
    fun me(): UserProfileResponse {
        val user = activeUser.require()

        return UserProfileResponse(
            id = user.id!!,
            firstName = user.firstName,
            lastName = user.lastName,
            email = user.email,
            createdAt = user.createdAt,
        )
    }

    /**
     * Closes the caller's own account. There is no `/api/users/{id}` variant, for the same
     * reason there is no such variant of the profile read: the account being closed is the
     * one the token identifies, so there is no id for a request to tamper with.
     *
     * Refused with 409 while the caller still owns an organization - see
     * [AccountClosureService].
     */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun closeAccount() = accountClosureService.closeOwnAccount()
}
