package com.tictac.io.timetracking

import com.tictac.io.project.ProjectRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Builds [TimeEntryResponse]s, resolving project names.
 *
 * Its own component because the name lookup is the one thing a response needs that the entry
 * row does not carry, and doing it per entry would be an N+1 on every page of a listing. The
 * list overload resolves every distinct project in one query instead, so a page of fifty
 * entries costs two queries however many projects they span.
 *
 * The project name is denormalised into the response rather than into the table on purpose:
 * a renamed project should show its current name everywhere, including on last year's
 * entries. Storing it at write time would freeze the name as it was, which is a decision to
 * make deliberately if invoices ever need it, not a side effect of avoiding a join.
 */
@Service
class TimeEntryResponses(
    private val projectRepository: ProjectRepository,
) {

    fun of(entry: TimeEntry, now: Instant): TimeEntryResponse {
        // Inside the same transaction the project is normally already in the persistence
        // context - resolved by ProjectAccess moments earlier - so this is a cache hit.
        val name = projectRepository.findById(entry.projectId).map { it.name }.orElse(UNKNOWN_PROJECT)

        return build(entry, name, now)
    }

    fun of(entries: List<TimeEntry>, now: Instant): List<TimeEntryResponse> {
        if (entries.isEmpty()) return emptyList()

        val namesById: Map<UUID, String> = projectRepository
            .findAllById(entries.map { it.projectId }.distinct())
            .associate { it.id!! to it.name }

        return entries.map { build(it, namesById[it.projectId] ?: UNKNOWN_PROJECT, now) }
    }

    private fun build(entry: TimeEntry, projectName: String, now: Instant) =
        TimeEntryResponse(
            id = entry.id!!,
            organizationId = entry.organizationId,
            projectId = entry.projectId,
            projectName = projectName,
            userId = entry.userId,
            description = entry.description,
            startedAt = entry.startedAt,
            endedAt = entry.endedAt,
            // Stored once stopped; computed from started_at while running.
            durationSeconds = entry.elapsedSecondsAt(now),
            running = entry.isRunning(),
            billable = entry.billable,
            createdAt = entry.createdAt,
            updatedAt = entry.updatedAt,
        )

    private companion object {
        /**
         * Unreachable while the foreign key stands - a time entry cannot name a project that
         * does not exist. A placeholder rather than a `!!` so that if it ever did happen, a
         * timesheet would render with one odd row instead of failing entirely.
         */
        const val UNKNOWN_PROJECT = "Unknown project"
    }
}
