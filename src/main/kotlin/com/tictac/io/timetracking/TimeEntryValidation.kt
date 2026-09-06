package com.tictac.io.timetracking

import java.time.Duration
import java.time.Instant

/**
 * Domain validation for the fields a request body cannot express structurally.
 *
 * Kept out of Bean Validation deliberately. `@NotBlank` would reject a whitespace-only title,
 * but as a 400 alongside "you omitted projectId" - and those are different problems. A
 * missing field means the request was malformed; a title of three spaces means the request
 * was fine and the value is not one the domain accepts. That is what 422 is for, and keeping
 * these checks here is what lets the two carry different statuses.
 *
 * Shared by the timer and manual entries so a title cannot be mandatory on one path and
 * optional on the other.
 */
object TimeEntryValidation {

    /**
     * Trimmed before it is judged. A title of three spaces is not a title, and storing one
     * would put a blank row in somebody's timesheet that looks like a rendering bug.
     */
    fun title(raw: String?): String {
        val title = raw?.trim().orEmpty()

        if (title.isEmpty()) {
            throw TimeEntryValidationException("Title is required")
        }
        if (title.length > TimeEntry.MAX_TITLE_LENGTH) {
            throw TimeEntryValidationException(
                "Title must be at most ${TimeEntry.MAX_TITLE_LENGTH} characters",
            )
        }

        return title
    }

    /**
     * Optional, and blank becomes null so "no description" has one representation rather
     * than three that sort and compare differently.
     */
    fun description(raw: String?): String? {
        val description = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        if (description.length > TimeEntry.MAX_DESCRIPTION_LENGTH) {
            throw TimeEntryValidationException(
                "Description must be at most ${TimeEntry.MAX_DESCRIPTION_LENGTH} characters",
            )
        }

        return description
    }

    /**
     * An interval has to run forwards and fit inside a day.
     *
     * The upper bound applies to manual entries and edits; a running timer meets it by being
     * clamped on stop instead, because refusing to stop a forgotten timer would leave someone
     * holding one they could never close.
     */
    fun range(startedAt: Instant, endedAt: Instant) {
        if (!endedAt.isAfter(startedAt)) {
            throw InvalidTimeRangeException("End time must be after start time")
        }

        if (Duration.between(startedAt, endedAt) > TimeEntry.MAX_DURATION) {
            throw InvalidTimeRangeException(
                "A time entry cannot be longer than ${TimeEntry.MAX_DURATION.toHours()} hours",
            )
        }
    }
}
