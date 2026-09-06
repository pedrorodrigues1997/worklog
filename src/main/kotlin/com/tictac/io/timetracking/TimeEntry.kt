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
        endedAt = now
        durationSeconds = Duration.between(startedAt, now).seconds
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
        durationSeconds ?: Duration.between(startedAt, now).seconds.coerceAtLeast(0)

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
        /** Bounded so a caller cannot post an unbounded blob into a TEXT column. */
        const val MAX_DESCRIPTION_LENGTH = 2000
    }
}
