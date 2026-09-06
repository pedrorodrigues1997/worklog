package com.tictac.io.project

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Note what is absent from every request here: an organization id. The organization is in
 * the URL, it has already been authorised, and accepting a second copy in the body would
 * create a value that can disagree with the one the permission check used.
 */
data class CreateProjectRequest(
    @field:NotBlank(message = "Project name is required")
    @field:Size(max = Project.MAX_NAME_LENGTH, message = "Project name must be at most 200 characters")
    val name: String?,

    @field:Size(max = Project.MAX_DESCRIPTION_LENGTH, message = "Description must be at most 2000 characters")
    val description: String? = null,
)

/**
 * A genuine partial update: every field is optional and an absent one is left alone.
 *
 * Absent and `null` mean the same thing - leave it - because distinguishing them would need
 * a wrapper type on every field to buy one capability. To *clear* a description, send an
 * empty string; blank descriptions are normalised to null on the way in, so `""` and "no
 * description" are the same stored value.
 */
data class UpdateProjectRequest(
    @field:Size(max = Project.MAX_NAME_LENGTH, message = "Project name must be at most 200 characters")
    val name: String? = null,

    @field:Size(max = Project.MAX_DESCRIPTION_LENGTH, message = "Description must be at most 2000 characters")
    val description: String? = null,

    /** false archives the project, true restores it. Archiving is a normal update. */
    val isActive: Boolean? = null,
)

/**
 * The person to assign, by user id, and they must already belong to the organization.
 *
 * Not by email, for the same reason ownership transfer is not: an email would let a caller
 * test which addresses have accounts, and bringing a new person into the company is an
 * invitation rather than a project assignment.
 */
data class AddProjectMemberRequest(
    @field:NotNull(message = "User id is required")
    val userId: UUID?,
)

data class ProjectResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Safe projection of an assigned user. Must never gain a password or password-hash field. */
data class ProjectMemberResponse(
    val userId: UUID,
    val firstName: String,
    val lastName: String,
    val email: String,
    val assignedAt: Instant,
)
