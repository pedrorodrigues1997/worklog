package com.tictac.io.project

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One person's assignment to one project.
 *
 * Note what this row does *not* carry: a role, and an organization id. No role, because
 * project-level permissions do not exist - what someone may do to a project is decided by
 * their organization role, and adding a second role dimension now would be a permission
 * framework built ahead of any requirement for one. No organization id, because it would be
 * derivable from `projects.organization_id` and therefore a second copy of the tenancy
 * answer that could disagree with the first.
 *
 * Both columns are immutable: an assignment is created and deleted, never re-pointed.
 */
@Entity
@Table(name = "project_members")
class ProjectMember(
    @Column(name = "project_id", nullable = false, updatable = false)
    val projectId: UUID,

    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun toString(): String = "ProjectMember(id=$id, projectId=$projectId, userId=$userId)"
}
