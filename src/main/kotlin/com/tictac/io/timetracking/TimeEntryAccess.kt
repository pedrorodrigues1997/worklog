package com.tictac.io.timetracking

import com.tictac.io.organization.OrganizationAccess
import com.tictac.io.organization.OrganizationContext
import com.tictac.io.organization.OrganizationRole
import com.tictac.io.project.ProjectAccess
import com.tictac.io.project.ProjectContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Not found, in another organization, or not this caller's to see - indistinguishable.
 *
 * Same reasoning as the organization and project equivalents. An organization MEMBER has no
 * legitimate way to learn that a colleague's time entry exists, so a 403 would leak it.
 */
class TimeEntryNotFoundException : RuntimeException("Time entry not found")

/** A timer is already running for this user in this organization. */
class TimerAlreadyRunningException :
    RuntimeException("A timer is already running in this organization; stop it before starting another")

/** Stop was called on an entry that is not running. */
class TimeEntryNotRunningException : RuntimeException("This time entry is not running")

/** New time cannot be tracked against an archived project. */
class ProjectNotActiveException :
    RuntimeException("This project is archived and cannot be used for new time entries")

/** The two ends of an entry do not make an interval. */
class InvalidTimeRangeException(message: String) : RuntimeException(message)

/**
 * What the caller may do with one time entry.
 *
 * [isOwn] is the distinction the whole permission model turns on: everything is permitted on
 * your own entry, and administrators additionally reach their organization's.
 */
data class TimeEntryContext(
    val organization: OrganizationContext,
    val timeEntry: TimeEntry,
) {
    val organizationId: UUID get() = organization.organizationId
    val callerId: UUID get() = organization.userId
    val isOwn: Boolean get() = timeEntry.userId == callerId
}

/**
 * The time-entry authorisation gate, layered on [OrganizationAccess] and [ProjectAccess].
 *
 * The full chain, every time:
 *
 * ```
 * access token → organization membership → organization role
 *              → project belongs to that organization → project accessible to caller
 *              → entry belongs to that organization → entry ownership or admin
 * ```
 *
 * Nothing here re-derives the earlier links; both lower gates are delegated to. What this
 * adds is the two time-entry specific questions - which entries this caller may touch, and
 * which projects they may record time against.
 *
 * **The two project questions are deliberately separate methods.** "Can this caller
 * administer the project?" is [ProjectAccess]'s answer and is unchanged. "Can this caller
 * record time against it?" is asked here, and differs in one way that matters: it also
 * requires the project to be *active* when the time is new. Editing history does not, since
 * a correction to last month's work may legitimately belong to a project archived since.
 *
 * ```
 * starting new time  →  project must be visible AND active
 * editing history    →  project must be visible; archived is fine
 * ```
 *
 * Note what tracking permission is *not*: it is not project assignment. An OWNER or ADMIN
 * may record time against any active project in their organization without being assigned to
 * it - see [requireProjectForNewTime].
 */
@Service
class TimeEntryAccess(
    private val organizationAccess: OrganizationAccess,
    private val projectAccess: ProjectAccess,
    private val timeEntryRepository: TimeEntryRepository,
) {

    /** Organization membership only, for operations not aimed at one existing entry. */
    @Transactional(readOnly = true)
    fun requireOrganization(organizationId: UUID, vararg allowedRoles: OrganizationRole): OrganizationContext =
        organizationAccess.require(organizationId, *allowedRoles)

    /**
     * Resolves an entry the caller is entitled to act on: their own, or - for an OWNER or
     * ADMIN - any entry in their organization.
     *
     * Read and write permission coincide, so this one method backs get, update and delete.
     * That is not laziness: a member who may not see a colleague's entry has no business
     * editing it either, and an administrator who may correct a timesheet must be able to
     * read it first. If the two ever diverge, this splits.
     *
     * **Administrators may act on their organization's entries.** Timesheets need
     * correcting - a member logs eight hours to the wrong project and leaves for the day -
     * and the alternative is either nobody can fix it or everybody can. Scoped strictly to
     * their own organization, and OWNER and ADMIN are treated alike here because both are
     * administrators of the company's work; billing and ownership are where they differ.
     *
     * @throws TimeEntryNotFoundException if the entry does not exist, belongs to another
     *   organization, is deleted, or belongs to another user and the caller is not an
     *   administrator. Deliberately one answer for all four.
     */
    @Transactional(readOnly = true)
    fun requireEntry(organizationId: UUID, timeEntryId: UUID): TimeEntryContext {
        val organization = organizationAccess.require(organizationId)

        val timeEntry = timeEntryRepository
            .findByIdAndOrganizationIdAndDeletedAtIsNull(timeEntryId, organizationId)
            ?: throw TimeEntryNotFoundException()

        val context = TimeEntryContext(organization, timeEntry)

        if (!context.isOwn && !organization.role.administersTimeEntries()) {
            throw TimeEntryNotFoundException()
        }

        return context
    }

    /**
     * The project a *new* entry will be recorded against: visible to the caller, and active.
     *
     * Visibility comes from [ProjectAccess], which already grants administrators every
     * project in the organization and members only what they are assigned to. That is the
     * decision this method inherits rather than re-implements: **an OWNER or ADMIN may track
     * time against any active project in their organization without being assigned to it.**
     * Requiring them to assign themselves to every project just to log their own hours would
     * make project membership mean two different things at once - who is on the work, and
     * who may record against it - and administrators would pollute the first to get the
     * second.
     *
     * @throws ProjectNotActiveException if the project is archived. Distinguishable from
     *   "not found" on purpose: the caller can see the project, so its state is not a secret,
     *   and "archived" is the one message that tells them what to do about it.
     */
    @Transactional(readOnly = true)
    fun requireProjectForNewTime(organizationId: UUID, projectId: UUID): ProjectContext {
        val project = projectAccess.require(organizationId, projectId)

        if (!project.project.isActive) {
            throw ProjectNotActiveException()
        }

        return project
    }

    /**
     * The project an *existing* entry is being moved to: visible to the caller, active or
     * not.
     *
     * Archived is deliberately allowed. Correcting which project last quarter's work belongs
     * to is exactly the case where the right answer is a project nobody is working on any
     * more, and refusing would force an administrator to un-archive a finished project to
     * fix a typo.
     */
    @Transactional(readOnly = true)
    fun requireProjectForHistoricalTime(organizationId: UUID, projectId: UUID): ProjectContext =
        projectAccess.require(organizationId, projectId)
}

/**
 * Whether this organization role reaches other people's time entries - listing, correcting
 * and deleting them.
 *
 * Kept in the time-tracking package for the same reason `administersProjects` lives in the
 * project one: whether a role administers *time entries* is a time-tracking decision, and
 * [OrganizationRole] should not accumulate a clause per downstream feature. The two happen
 * to name the same roles today and are still two different questions.
 */
internal fun OrganizationRole.administersTimeEntries(): Boolean =
    this == OrganizationRole.OWNER || this == OrganizationRole.ADMIN
