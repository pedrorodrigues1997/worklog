package com.tictac.io.organization

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One person's membership of one organization, and the role they hold there.
 *
 * This is the authorization record. A request is allowed to touch an organization if and
 * only if a row exists here for (that organization, the caller from the access token).
 *
 * [organizationId] and [userId] are plain columns rather than `@ManyToOne`, for the same
 * reason as [com.tictac.io.authentication.token.RefreshToken]: these are separate
 * aggregates, referencing across a domain boundary by id keeps the modules independent,
 * and the foreign keys still exist in the database. It also stops a lazy association from
 * quietly pulling a whole `User` - password hash included - into a response mapper.
 *
 * Both are immutable. Moving a membership between organizations or users is not an
 * operation; the row is created and deleted, never re-pointed.
 */
@Entity
@Table(name = "organization_members")
class OrganizationMember(
    @Column(name = "organization_id", nullable = false, updatable = false)
    val organizationId: UUID,

    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    var role: OrganizationRole,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun toString(): String =
        "OrganizationMember(id=$id, organizationId=$organizationId, userId=$userId, role=$role)"
}
