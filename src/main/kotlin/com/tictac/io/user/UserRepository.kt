package com.tictac.io.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {

    /** [email] must already be normalised by the caller. */
    fun findByEmail(email: String): User?

    /** [email] must already be normalised by the caller. */
    fun existsByEmail(email: String): Boolean
}
