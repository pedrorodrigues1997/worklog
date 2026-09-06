package com.tictac.io.timetracking

import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Time entries are addressed under the organization that owns them, so the tenant is in
 * every path and is checked on every call. No authorisation logic lives here - both path
 * variables are untrusted and go straight to a service, each of which opens with
 * [TimeEntryAccess].
 *
 * The timer lives at `/time-entries/timer`; see [TimerController]. A literal path segment
 * outranks a variable one in Spring's pattern matching, so it never collides with
 * `/{timeEntryId}` here.
 */
@RestController
@RequestMapping("/api/organizations/{organizationId}/time-entries")
class TimeEntryController(
    private val timeEntryService: TimeEntryService,
) {

    /** A manual entry, for work that was never timed. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable organizationId: UUID,
        @Valid @RequestBody request: CreateTimeEntryRequest,
    ): TimeEntryResponse = timeEntryService.create(organizationId, request)

    /**
     * A page of entries, newest first. Members see their own; administrators see the
     * organization's.
     *
     * `from` is inclusive and `to` exclusive, both ISO-8601 instants matched against
     * `startedAt`, so consecutive periods tile without double-counting a boundary entry.
     * Ordering is fixed server-side and is not a parameter.
     */
    @GetMapping
    fun list(
        @PathVariable organizationId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${PageResponse.DEFAULT_PAGE_SIZE}") size: Int,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) userId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) billable: Boolean?,
    ): PageResponse<TimeEntryResponse> =
        timeEntryService.list(
            organizationId = organizationId,
            // Clamped here rather than rejected: a client asking for page -1 or ten thousand
            // rows has made a mistake, and the cap is what stops one request pulling a
            // company's year of work into memory.
            page = page.coerceAtLeast(0),
            size = size.coerceIn(1, PageResponse.MAX_PAGE_SIZE),
            projectId = projectId,
            userId = userId,
            from = from,
            to = to,
            billable = billable,
        )

    @GetMapping("/{timeEntryId}")
    fun get(
        @PathVariable organizationId: UUID,
        @PathVariable timeEntryId: UUID,
    ): TimeEntryResponse = timeEntryService.get(organizationId, timeEntryId)

    @PatchMapping("/{timeEntryId}")
    fun update(
        @PathVariable organizationId: UUID,
        @PathVariable timeEntryId: UUID,
        @Valid @RequestBody request: UpdateTimeEntryRequest,
    ): TimeEntryResponse = timeEntryService.update(organizationId, timeEntryId, request)

    /** Soft delete - see [TimeEntryService.delete] for why the row survives. */
    @DeleteMapping("/{timeEntryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable organizationId: UUID,
        @PathVariable timeEntryId: UUID,
    ) = timeEntryService.delete(organizationId, timeEntryId)
}

/**
 * The running timer.
 *
 * Separate from [TimeEntryController] because it is a different thing to reason about: two
 * of these three endpoints are commands about *now*, and the third is the one a client calls
 * on every page load to rediscover state it does not own.
 */
@RestController
@RequestMapping("/api/organizations/{organizationId}/time-entries")
class TimerController(
    private val timerService: TimerService,
) {

    /** `started_at` is server time. This endpoint accepts no timestamp at all. */
    @PostMapping("/timer")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(
        @PathVariable organizationId: UUID,
        @Valid @RequestBody request: StartTimerRequest,
    ): TimeEntryResponse = timerService.start(organizationId, request)

    /**
     * The caller's running timer, or `204 No Content` when there is none.
     *
     * 204 rather than a 200 carrying `null`, and certainly rather than a 404: no timer
     * running is an ordinary state of the world, not a missing resource and not an error.
     *
     * This is how a client recovers after a refresh or on another device. The elapsed time
     * in the response is computed from the stored `startedAt`, so it is correct however long
     * the browser was closed.
     */
    @GetMapping("/timer")
    fun current(@PathVariable organizationId: UUID): ResponseEntity<TimeEntryResponse> =
        timerService.current(organizationId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.noContent().build()

    /** `ended_at` is server time. This endpoint accepts no body. */
    @PostMapping("/{timeEntryId}/stop")
    fun stop(
        @PathVariable organizationId: UUID,
        @PathVariable timeEntryId: UUID,
    ): TimeEntryResponse = timerService.stop(organizationId, timeEntryId)
}
