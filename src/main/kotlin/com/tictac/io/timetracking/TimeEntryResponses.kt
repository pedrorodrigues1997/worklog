package com.tictac.io.timetracking

import com.tictac.io.project.ProjectCategoryRepository
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
    private val projectCategoryRepository: ProjectCategoryRepository,
) {

    fun of(entry: TimeEntry, now: Instant): TimeEntryResponse {
        // Inside the same transaction both are normally already in the persistence context -
        // resolved by ProjectAccess moments earlier - so these are cache hits.
        val projectName = projectRepository.findById(entry.projectId).map { it.name }.orElse(UNKNOWN_PROJECT)
        val categoryName = entry.projectCategoryId
            ?.let { projectCategoryRepository.findById(it).map { category -> category.name }.orElse(null) }

        return build(entry, projectName, categoryName, now)
    }

    fun of(entries: List<TimeEntry>, now: Instant): List<TimeEntryResponse> {
        if (entries.isEmpty()) return emptyList()

        val projectNames: Map<UUID, String> = projectRepository
            .findAllById(entries.map { it.projectId }.distinct())
            .associate { it.id!! to it.name }

        // One query for every category on the page, however many entries share them - and
        // none at all when nothing on the page is categorised.
        val categoryNames: Map<UUID, String> = entries.mapNotNull { it.projectCategoryId }.distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { ids -> projectCategoryRepository.findAllById(ids).associate { it.id!! to it.name } }
            .orEmpty()

        return entries.map {
            build(
                it,
                projectNames[it.projectId] ?: UNKNOWN_PROJECT,
                it.projectCategoryId?.let { id -> categoryNames[id] },
                now,
            )
        }
    }

    private fun build(entry: TimeEntry, projectName: String, categoryName: String?, now: Instant) =
        TimeEntryResponse(
            id = entry.id!!,
            organizationId = entry.organizationId,
            projectId = entry.projectId,
            projectName = projectName,
            projectCategoryId = entry.projectCategoryId,
            projectCategoryName = categoryName,
            userId = entry.userId,
            title = entry.title,
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
