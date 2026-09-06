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
 * A kind of work within one project - "Development", "Design", "Meetings".
 *
 * A subdivision of its project, never an entity in its own right. It has no organization of
 * its own (it inherits one through [projectId]), no members, and no lifecycle beyond active
 * and inactive. Two projects that both define "Development" have two unrelated rows, because
 * a category only means something relative to the project that defines it.
 *
 * [projectId] and [createdByUserId] are both immutable. Moving a category between projects
 * is not an operation: every time entry that names it would silently change which project's
 * work it describes. If it is ever needed it deserves its own explicit operation, the way
 * ownership transfer does.
 *
 * [createdByUserId] is provenance only. It is never an authorisation input - what someone may
 * do to a category comes from their organisation role, not from having created it.
 */
@Entity
@Table(name = "project_categories")
class ProjectCategory(
    @Column(name = "project_id", nullable = false, updatable = false)
    val projectId: UUID,

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    val createdByUserId: UUID,

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    var name: String,

    @Column(name = "description")
    var description: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "project_category_id", nullable = false, updatable = false)
    var id: UUID? = null

    /** Archive flag. Inactive means "not selectable for new work", never "deleted". */
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    override fun toString(): String =
        "ProjectCategory(id=$id, projectId=$projectId, name=$name, active=$isActive)"

    companion object {
        const val MAX_NAME_LENGTH = 100
        const val MAX_DESCRIPTION_LENGTH = 2000
    }
}
