package com.tictac.io.timetracking

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The running timer: start, stop, and "what am I doing right now?".
 *
 * The database is the source of truth for a running timer, not the browser. A closed tab, a
 * refresh, a different device - none of them affect a row whose `ended_at` is still null, and
 * `GET /timer` is how a client rediscovers it. Elapsed time is always computed from
 * `started_at`; nothing ticks server-side and no row is rewritten while a timer runs.
 */
@Service
class TimerService(
    private val timeEntryAccess: TimeEntryAccess,
    private val timeEntryRepository: TimeEntryRepository,
    private val timeEntryResponses: TimeEntryResponses,
) {

    /**
     * Starts a timer. Every check happens before anything is written.
     *
     * `started_at` is server time, always. The endpoint takes no timestamp at all, so a
     * client cannot backdate a timer to inflate a day - that is what the manual-entry
     * endpoint is for, where the timestamps are visible as what they are.
     *
     * The one-running-timer rule is checked here for a clean error and enforced by the
     * partial unique index for correctness. Two simultaneous requests can both pass the
     * check; only one can pass the index, and the loser is translated back into the same
     * 409 the pre-check would have produced. The pre-check is the manners, the index is the
     * guarantee.
     *
     * Nothing is stopped implicitly. Starting a second timer is an error, not an instruction
     * to end the first - a client that silently closed a half-hour of someone's work because
     * a button was double-clicked would be worse than a rejected request.
     */
    @Transactional
    fun start(organizationId: UUID, request: StartTimerRequest): TimeEntryResponse {
        val organization = timeEntryAccess.requireOrganization(organizationId)

        // Safe: validated as @NotNull by the controller.
        val project = timeEntryAccess.requireProjectForNewTime(organizationId, request.projectId!!)

        if (runningTimer(organizationId, organization.userId) != null) {
            throw TimerAlreadyRunningException()
        }

        val now = Instant.now()
        val entry = TimeEntry(
            organizationId = organizationId,
            projectId = project.projectId,
            userId = organization.userId,
            startedAt = now,
        ).apply {
            description = request.description?.trim()?.takeIf { it.isNotEmpty() }
            billable = request.billable
        }

        val saved = try {
            // saveAndFlush so the partial unique index is evaluated inside this method
            // rather than at commit, where the violation would surface as a 500.
            timeEntryRepository.saveAndFlush(entry)
        } catch (ex: DataIntegrityViolationException) {
            throw TimerAlreadyRunningException()
        }

        return timeEntryResponses.of(saved, now)
    }

    /**
     * Stops a running timer, deriving the duration from two server-side timestamps.
     *
     * **Only the owner may stop their own timer**, administrator or not. Stopping is not a
     * correction, it is an assertion about what someone is doing at this second, and nobody
     * else is in a position to make it. An administrator who needs to fix an abandoned timer
     * can edit the entry and set its end explicitly, which records a considered timestamp
     * rather than "whenever the admin happened to notice".
     *
     * Calling it twice is a 409, not a silent success and not a corrupted row: the second
     * call finds `ended_at` already set and refuses before touching anything, so the first
     * stop's timestamp and duration stand.
     */
    @Transactional
    fun stop(organizationId: UUID, timeEntryId: UUID): TimeEntryResponse {
        val context = timeEntryAccess.requireEntry(organizationId, timeEntryId)

        if (!context.isOwn) {
            // Reachable only by an administrator - a member would have been refused as
            // "not found" already - so 403 tells them nothing they could not already see.
            throw AccessDeniedException("Only the owner of a running timer may stop it")
        }
        if (!context.timeEntry.isRunning()) {
            throw TimeEntryNotRunningException()
        }

        val now = Instant.now()
        context.timeEntry.stopAt(now)

        return timeEntryResponses.of(context.timeEntry, now)
    }

    /**
     * The caller's running timer in this organization, or null.
     *
     * Per organization, not per user: someone consulting for two companies can legitimately
     * have a timer going in each, and the constraint is scoped the same way.
     */
    @Transactional(readOnly = true)
    fun current(organizationId: UUID): TimeEntryResponse? {
        val organization = timeEntryAccess.requireOrganization(organizationId)

        return runningTimer(organizationId, organization.userId)
            ?.let { timeEntryResponses.of(it, Instant.now()) }
    }

    private fun runningTimer(organizationId: UUID, userId: UUID): TimeEntry? =
        timeEntryRepository.findByOrganizationIdAndUserIdAndEndedAtIsNullAndDeletedAtIsNull(organizationId, userId)
}
