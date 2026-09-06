package com.tictac.io.project

import java.time.Instant
import java.util.UUID

/**
 * Neither request carries an organization id or a project id - both are in the URL and have
 * already been authorised, and a second copy in the body would be a value that can disagree
 * with the one the permission check used.
 *
 * Name and description are validated in the service rather than by Bean Validation, so that
 * "blank after trimming" and "too long" give the same 422 as every other domain rule here
 * instead of the 400 a structural failure produces.
 */
data class CreateProjectCategoryRequest(
    val name: String? = null,
    val description: String? = null,
)

/**
 * A partial update: absent fields are left alone, following the convention the project and
 * time-entry updates use. Send an empty description to clear one.
 *
 * There is no `projectId` and no `createdByUserId`. A category cannot be moved between
 * projects - every time entry naming it would silently change which project's work it
 * describes - so that is not a request anyone can express here.
 */
data class UpdateProjectCategoryRequest(
    val name: String? = null,
    val description: String? = null,
    /** false retires the category, true brings it back. */
    val isActive: Boolean? = null,
)

data class ProjectCategoryResponse(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val description: String?,
    val isActive: Boolean,
    val createdByUserId: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
)
