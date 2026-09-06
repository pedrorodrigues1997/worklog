package com.tictac.io.project

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A body of work inside one organization. Time entries will eventually point here.
 *
 * [organizationId] is a plain column rather than a `@ManyToOne`, matching every other
 * cross-aggregate reference in this codebase, and it is immutable: moving a project between
 * organizations is not an operation. It would carry every time entry underneath it across a
 * tenant boundary, which is the one thing the whole authorisation model exists to prevent.
 *
 * As with [com.tictac.io.organization.Organization] there is no `isArchived()`-style helper:
 * Hibernate resolves property access through getters here, so a no-argument `isX()` on an
 * entity is indistinguishable from a mapped boolean property and would break
 * `ddl-auto=validate` at startup. [isActive] is a real column, which is why it is fine.
 */
@Entity
@Table(name = "projects")
class Project(
    @Column(name = "organization_id", nullable = false, updatable = false)
    val organizationId: UUID,

    @Column(name = "project_name", nullable = false, length = MAX_NAME_LENGTH)
    var name: String,

    @Column(name = "description")
    var description: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "project_id", nullable = false, updatable = false)
    var id: UUID? = null

    /** Archive flag. Inactive means "not selectable for new work", never "deleted". */
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    /**
     * The one lifecycle callback in the codebase, and it earns its place: `updated_at` is
     * NOT NULL and has to stay honest across every write path, present and future.
     * Assigning it by hand in the service works right up until the second path forgets.
     */
    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    override fun toString(): String =
        "Project(id=$id, organizationId=$organizationId, name=$name, active=$isActive)"

    companion object {
        const val MAX_NAME_LENGTH = 200

        /** Bounded so a caller cannot post an unbounded blob into a TEXT column. */
        const val MAX_DESCRIPTION_LENGTH = 2000
    }
}
