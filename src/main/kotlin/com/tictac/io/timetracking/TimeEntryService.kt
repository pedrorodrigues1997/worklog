package com.tictac.io.timetracking

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class TimeEntryService(
    private val timeEntryAccess: TimeEntryAccess,
    private val timeEntryRepository: TimeEntryRepository,
    private val timeEntryResponses: TimeEntryResponses,
) {

    /**
     * Records work that was never timed. Both ends come from the client; the duration does
     * not - it is derived here, so a request cannot claim eight hours for a twenty-minute
     * interval.
     *
     * The project must be active, the same as starting a timer: this is new time being
     * recorded, and the rule is about when work is logged rather than how. Moving an
     * *existing* entry onto an archived project is allowed - see [update].
     *
     * Overlapping entries are permitted and are not an error. Someone may legitimately
     * record a client call that ran through a stretch of development work, and deciding
     * which of two overlapping intervals is "wrong" is a reporting question, not something
     * to guess at write time. The one thing that cannot overlap is two *running* timers,
     * which is a different constraint entirely.
     */
    @Transactional
    fun create(organizationId: UUID, request: CreateTimeEntryRequest): TimeEntryResponse {
        val organization = timeEntryAccess.requireOrganization(organizationId)

        // Safe: all three validated as @NotNull by the controller.
        val project = timeEntryAccess.requireProjectForNewTime(organizationId, request.projectId!!)
        val startedAt = request.startedAt!!
        val endedAt = request.endedAt!!

        requireValidRange(startedAt, endedAt)

        val entry = TimeEntry(
            organizationId = organizationId,
            projectId = project.projectId,
            userId = organization.userId,
            startedAt = startedAt,
        ).apply {
            this.endedAt = endedAt
            description = request.description?.trim()?.takeIf { it.isNotEmpty() }
            billable = request.billable
            recalculateDuration()
        }

        return timeEntryResponses.of(timeEntryRepository.save(entry), Instant.now())
    }

    @Transactional(readOnly = true)
    fun get(organizationId: UUID, timeEntryId: UUID): TimeEntryResponse =
        timeEntryResponses.of(timeEntryAccess.requireEntry(organizationId, timeEntryId).timeEntry, Instant.now())

    /**
     * Corrects an entry. The caller's own, or - for an OWNER or ADMIN - anyone's in their
     * organization; see [TimeEntryAccess.requireEntry] for why that is the right set.
     *
     * The organization and the user are not editable and are not in the request, so there is
     * no shape of PATCH that moves billable history between tenants or reattributes it.
     *
     * A project change is re-authorised from scratch against the *caller's* project access,
     * so an id from another organization resolves to nothing however valid it looks. Archived
     * projects are accepted here: a correction to finished work may well belong to a project
     * nobody is on any more.
     *
     * Any change to either timestamp recalculates the duration. A client cannot supply one.
     */
    @Transactional
    fun update(organizationId: UUID, timeEntryId: UUID, request: UpdateTimeEntryRequest): TimeEntryResponse {
        val context = timeEntryAccess.requireEntry(organizationId, timeEntryId)
        val entry = context.timeEntry

        request.projectId?.let {
            entry.projectId = timeEntryAccess.requireProjectForHistoricalTime(organizationId, it).projectId
        }
        request.description?.let { entry.description = it.trim().takeIf { trimmed -> trimmed.isNotEmpty() } }
        request.billable?.let { entry.billable = it }
        request.startedAt?.let { entry.startedAt = it }
        request.endedAt?.let { entry.endedAt = it }

        // Validated against the state after the patch, not the request, so changing one end
        // of an existing interval cannot quietly invert it.
        entry.endedAt?.let { requireValidRange(entry.startedAt, it) }
        entry.recalculateDuration()

        return timeEntryResponses.of(entry, Instant.now())
    }

    /**
     * Soft delete. The row stays; `deleted_at` is stamped and every read excludes it.
     *
     * Hard deletion was the alternative and was rejected. This codebase preserves
     * consistently - organizations and users soft-delete, projects archive, memberships
     * survive account closure - and time entries are the most consequential data in it:
     * they are what a customer eventually invoices from. A mis-clicked DELETE that
     * irreversibly removes a month of billable work is a support incident with no recovery
     * path, whereas a tombstone is one UPDATE away from being undone by hand.
     *
     * That is deliberately not an audit system, which is a separate feature: there is no
     * record of who deleted an entry or why, and no restore endpoint. It is the cheapest
     * thing that makes the data recoverable at all.
     *
     * Deleting a *running* timer frees the one-timer slot immediately - the partial unique
     * index excludes soft-deleted rows - so a user who starts a timer by mistake is not
     * locked out of starting the right one.
     */
    @Transactional
    fun delete(organizationId: UUID, timeEntryId: UUID) {
        val context = timeEntryAccess.requireEntry(organizationId, timeEntryId)

        context.timeEntry.deletedAt = Instant.now()
    }

    /**
     * A page of entries, newest first.
     *
     * **A MEMBER sees only their own entries; an OWNER or ADMIN sees the organization's.**
     * The member scope is applied as a predicate rather than as a check on the requested
     * filter, so a member who asks for a colleague's entries gets an empty page - no leak,
     * and no special-cased error to get wrong.
     *
     * Ordering is fixed server-side and is `started_at DESC, id DESC`. The tiebreaker is not
     * decoration: without it, entries sharing a timestamp could shuffle between pages and a
     * client would see one twice and another never.
     *
     * Nothing loads the organization's whole table - the page size is capped in the
     * controller, and the filters push down into SQL rather than into a `filter {}` here.
     */
    @Transactional(readOnly = true)
    fun list(
        organizationId: UUID,
        page: Int,
        size: Int,
        projectId: UUID? = null,
        userId: UUID? = null,
        from: Instant? = null,
        to: Instant? = null,
        billable: Boolean? = null,
    ): PageResponse<TimeEntryResponse> {
        val organization = timeEntryAccess.requireOrganization(organizationId)

        val scopedUserId = organization.userId.takeUnless { organization.role.administersTimeEntries() }

        val pageable = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")),
        )

        val results = timeEntryRepository.findAll(
            TimeEntryFilters.of(
                organizationId = organizationId,
                scopedUserId = scopedUserId,
                projectId = projectId,
                userId = userId,
                from = from,
                to = to,
                billable = billable,
            ),
            pageable,
        )

        return PageResponse(
            content = timeEntryResponses.of(results.content, Instant.now()),
            page = results.number,
            size = results.size,
            totalElements = results.totalElements,
            totalPages = results.totalPages,
        )
    }

    private fun requireValidRange(startedAt: Instant, endedAt: Instant) {
        if (!endedAt.isAfter(startedAt)) {
            throw InvalidTimeRangeException("End time must be after start time")
        }
    }
}
