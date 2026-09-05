package com.tictac.io.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A person who can sign in. Deliberately a plain class rather than a data class:
 * a generated `equals`/`hashCode` over all columns is wrong for a JPA entity, and
 * a generated `toString` would print the password hash.
 */
@Entity
@Table(name = "users")
class User(
    @Column(name = "first_name", nullable = false, length = 100)
    var firstName: String,

    @Column(name = "last_name", nullable = false, length = 100)
    var lastName: String,

    /** Always stored normalised (trimmed, lowercased) - see RegistrationService. */
    @Column(name = "email", nullable = false, unique = true, length = 320)
    var email: String,

    /** Argon2id hash produced by the configured PasswordEncoder. Never the raw password. */
    @Column(name = "password_hash", length = 255)
    var passwordHash: String? = null,
) {
    /** Assigned by Hibernate on persist; null until then. Never set this by hand. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    /** Set once at construction time and mapped as non-updatable. */
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    /** Soft-delete marker. Null means the user is active. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    /**
     * Never include [passwordHash] here: entities end up in log lines and exception
     * messages, and that is exactly how hashes leak.
     */
    override fun toString(): String = "User(id=$id, email=$email)"
}
