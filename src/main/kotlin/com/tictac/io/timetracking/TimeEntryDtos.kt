package com.tictac.io.timetracking

import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/**
 * Every timestamp on the wire is ISO-8601 with an offset, and is parsed into an [Instant] -
 * an absolute point on the timeline, stored as `timestamptz`. A client in Dubai and a client
 * in Lisbon describing the same moment send different strings and get the same row.
 *
 * `2026-09-06T10:30:00Z` and `2026-09-06T14:30:00+04:00` are the same instant and are
 * accepted interchangeably. A local time with no offset is rejected: "10:30" is not a moment
 * until someone says where, and guessing on the client's behalf is how a timesheet ends up
 * four hours out.
 *
 * **Where the validation lives, and why the statuses differ.** Ids are `@NotNull` and a
 * missing one is a malformed request - 400, from Bean Validation, consistent with the rest
 * of the API. Titles, descriptions and time ranges are checked in the service instead and
 * fail with 422: those are well-formed requests carrying values the domain will not accept,
 * and the distinction is worth keeping because "you forgot a field" and "that title is only
 * spaces" are different problems for a client to handle.
 */
data class StartTimerRequest(
    @field:NotNull(message = "Project id is required")
    val projectId: UUID?,

    /** Optional. When set it must belong to [projectId] and be active. */
    val projectCategoryId: UUID? = null,

    val title: String? = null,
    val description: String? = null,

    /**
     * Defaults to false. Never mark someone's time billable because they left a field out -
     * the mistake that costs money is the one nobody made deliberately.
     */
    val billable: Boolean = false,
)

/**
 * A manual entry. Both ends are required and both come from the client, because this is how
 * work that was never timed gets recorded.
 *
 * There is deliberately no duration field. The server derives it from the two timestamps, so
 * a client-supplied duration has nowhere to land rather than being accepted and ignored.
 */
data class CreateTimeEntryRequest(
    @field:NotNull(message = "Project id is required")
    val projectId: UUID?,

    val projectCategoryId: UUID? = null,

    val title: String? = null,
    val description: String? = null,

    @field:NotNull(message = "Start time is required")
    val startedAt: Instant?,

    @field:NotNull(message = "End time is required")
    val endedAt: Instant?,

    val billable: Boolean = false,
)

/**
 * A partial update: absent fields are left alone, following the same convention as
 * [com.tictac.io.project.UpdateProjectRequest]. Send an empty description to clear one.
 *
 * Note what cannot be changed: the organization and the user. Neither appears here, so
 * moving billable history across a tenant boundary or reattributing someone else's work is
 * not a request anyone can express. [endedAt] cannot be cleared either - "un-stopping" an
 * entry would resurrect a second running timer, and resuming is not an operation.
 *
 * [clearProjectCategory] exists because absent and null are the same thing under this
 * convention, and removing a category needs to be sayable. An explicit flag rather than a
 * wrapper type on every nullable field: it costs one boolean, reads plainly in a request
 * body, and does not depend on how the JSON library distinguishes "absent" from "null".
 */
data class UpdateTimeEntryRequest(
    val projectId: UUID? = null,
    val projectCategoryId: UUID? = null,
    /** true removes the category; [projectCategoryId] is then ignored. */
    val clearProjectCategory: Boolean = false,

    val title: String? = null,
    val description: String? = null,

    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val billable: Boolean? = null,
)

/**
 * [durationSeconds] is the stored duration once the entry is stopped, and the elapsed time
 * since [startedAt] as of this response while it runs. The running value is computed per
 * request and never written back - the database does not tick.
 *
 * [projectCategoryId] and [projectCategoryName] are both null for time tracked straight
 * against a project, which is an ordinary and fully supported shape.
 */
data class TimeEntryResponse(
    val id: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val projectName: String,
    val projectCategoryId: UUID?,
    val projectCategoryName: String?,
    val userId: UUID,
    val title: String,
    val description: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val durationSeconds: Long,
    val running: Boolean,
    val billable: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * A page of results.
 *
 * Defined here rather than serialising Spring Data's `Page`, whose JSON is an implementation
 * detail that has changed between Boot versions and carries fields no client needs. This is
 * the first paginated endpoint in the API; whatever shape it takes becomes the convention.
 */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50

        /** Capped so one request cannot ask the database for a year of a company's work. */
        const val MAX_PAGE_SIZE = 200
    }
}
