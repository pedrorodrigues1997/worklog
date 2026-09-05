package com.tictac.io.user

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
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
}
