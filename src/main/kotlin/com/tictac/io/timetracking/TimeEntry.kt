package com.tictac.io.timetracking

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One stretch of tracked time.
 *
 * Running and stopped are the same row in two states, distinguished by [endedAt] alone.
 * There is no status column, because a second representation of the same fact is a second
 * thing that can be wrong. A database CHECK keeps [durationSeconds] in step with it.
 *
 * All three references are plain id columns, as everywhere else in this codebase, and all
 * three are immutable. [organizationId] and [userId] must never move - the first would
 * carry billable history across a tenant boundary, the second would reattribute someone
 * else's work. The project *can* be corrected, which is why [projectId] is the one `var`;
 * see [TimeEntryService.update].
 *
 * [isRunning] is annotated `@Transient` for a real reason - see the note on it.
 */
@Entity
@Table(name = "time_entries")
class TimeEntry(
    @Column(name = "organization_id", nullable = false, updatable = false)
    val organizationId: UUID,

    @Column(name = "project_id", nullable = false)
    var projectId: UUID,

    /**
     * The kind of work, or null for time tracked straight against the project.
     *
     * Optional by design: a project with no categories is valid, and nobody should have to
     * invent a taxonomy to log an hour. When it is set, a composite foreign key guarantees
     * the category belongs to [projectId] - the pair moves together or not at all.
     */
    @Column(name = "project_category_id")
    var projectCategoryId: UUID? = null,

    /** What the work was. Required, trimmed, and never blank - see TimeEntryService. */
    @Column(name = "title", nullable = false, length = MAX_TITLE_LENGTH)
    var title: String = "",

    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "time_entry_id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "description")
    var description: String? = null

    /** Null while the timer runs. Setting it is what stops the entry. */
    @Column(name = "ended_at")
    var endedAt: Instant? = null

    /** Derived from the timestamps, never accepted from a client. Null while running. */
    @Column(name = "duration_seconds")
    var durationSeconds: Long? = null

    @Column(name = "billable", nullable = false)
    var billable: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    /** Soft-delete marker. Null means the entry is live. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    /**
     * Stops the entry, deriving the duration from the two server-side timestamps.
     *
     * The only place [durationSeconds] and [endedAt] are set together, so they cannot fall
     * out of step - and the only reason a client-supplied duration has nowhere to land.
     */
    fun stopAt(now: Instant) {
        // Capped rather than refused. A timer someone forgot on Friday should not be
        // impossible to close on Monday, and it should not record the weekend either. The
        // recorded end is the cap; if the real one differs, the entry can be edited - which
        // is the honest way to state a duration nobody was actually present for.
        val cap = startedAt.plus(MAX_DURATION)

        endedAt = if (now.isAfter(cap)) cap else now
        durationSeconds = Duration.between(startedAt, endedAt).seconds
    }

    /** Recomputes the duration after an edit. Null while the entry is still running. */
    fun recalculateDuration() {
        durationSeconds = endedAt?.let { Duration.between(startedAt, it).seconds }
    }

    /**
     * Elapsed time as of [now]: the stored duration once stopped, and the time since
     * [startedAt] while running.
     *
     * A running entry's elapsed time is computed on read and never written back. The
     * database does not tick, and one row per user per organization does not need to be
     * updated every second to answer a question arithmetic already answers.
     */
    fun elapsedSecondsAt(now: Instant): Long =
        durationSeconds
            // Capped the same way stopping is, so a running timer never displays a number
            // larger than the one it would record.
            ?: Duration.between(startedAt, now).seconds.coerceIn(0, MAX_DURATION.seconds)

    /**
     * `@Transient` is load-bearing, not decoration. Hibernate resolves property access
     * through getters on these entities, so a no-argument `isX()` would otherwise be taken
     * for a mapped boolean column named `running` and fail `ddl-auto=validate` at startup.
     * The other entities dodge this by having no such helper at all; here the concept is
     * used often enough to be worth naming.
     */
    @Transient
    fun isRunning(): Boolean = endedAt == null

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    override fun toString(): String =
        "TimeEntry(id=$id, organizationId=$organizationId, projectId=$projectId, " +
            "userId=$userId, running=${endedAt == null})"

    companion object {
        const val MAX_TITLE_LENGTH = 255

        /** Bounded so a caller cannot post an unbounded blob into a TEXT column. */
        const val MAX_DESCRIPTION_LENGTH = 5000

        /**
         * The longest a single entry may span.
         *
         * A day is the natural ceiling for "time somebody spent working": anything longer is
         * a forgotten timer or a typo, not a record worth billing. It bounds both ends -
         * stopping clamps to it, and a manual entry beyond it is refused - so no path
         * produces an entry claiming a week.
         */
        val MAX_DURATION: Duration = Duration.ofHours(24)
    }
}
