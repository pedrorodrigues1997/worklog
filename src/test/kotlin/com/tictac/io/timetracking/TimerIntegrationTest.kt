package com.tictac.io.timetracking

import com.jayway.jsonpath.JsonPath
import com.tictac.io.organization.OrganizationRole
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

@DisplayName("Timer: start, stop and the running timer")
class TimerIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var bob: TestUser
    private var organizationId = UUID.randomUUID()
    private var assignedProject = UUID.randomUUID()
    private var unassignedProject = UUID.randomUUID()

    @BeforeEach
    fun createOrganizationWithProjects() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        bob = newUser("bob@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, bob, OrganizationRole.MEMBER)

        assignedProject = createProject(organizationId, owner, "Website Redesign")
        unassignedProject = createProject(organizationId, owner, "Mobile App")
        assignToProject(organizationId, assignedProject, bob, owner)
    }

    private fun timerPath() = "/api/organizations/$organizationId/time-entries/timer"

    private fun start(projectId: UUID, user: TestUser, billable: Boolean = false) =
        postJson(timerPath(), """{"projectId":"$projectId","billable":$billable}""", user.accessToken)

    private fun stop(timeEntryId: UUID, user: TestUser) =
        postJson(
            "/api/organizations/$organizationId/time-entries/$timeEntryId/stop",
            "",
            user.accessToken,
        )

    // --- who may start ------------------------------------------------------------------

    @Test
    fun `a member can start a timer on a project they are assigned to`() {
        val before = Instant.now()

        val body = start(assignedProject, bob)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.projectId").value(assignedProject.toString()))
            .andExpect(jsonPath("$.projectName").value("Website Redesign"))
            .andExpect(jsonPath("$.userId").value(bob.id.toString()))
            .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
            .andExpect(jsonPath("$.running").value(true))
            .andExpect(jsonPath("$.endedAt").doesNotExist())
            .andReturn().response.contentAsString

        val entry = timeEntryRepository.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow()

        // Server-generated, not client-supplied - the endpoint takes no timestamp at all.
        assertThat(entry.startedAt).isBetween(before, Instant.now())
        assertThat(entry.endedAt).isNull()
        assertThat(entry.durationSeconds).isNull()
        assertThat(entry.userId).isEqualTo(bob.id)
    }

    @Test
    fun `a member cannot start a timer on a project they are not assigned to`() {
        start(unassignedProject, bob).andExpect(status().isNotFound)

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `an owner and an admin can start a timer without being assigned to the project`() {
        // The documented decision: administering the organization is enough to track time
        // against its projects. Neither of them is a project member.
        assertThat(isAssigned(unassignedProject, owner)).isFalse()
        assertThat(isAssigned(unassignedProject, admin)).isFalse()

        start(unassignedProject, owner).andExpect(status().isCreated)
        start(unassignedProject, admin).andExpect(status().isCreated)

        assertThat(timeEntryRepository.count()).isEqualTo(2)
        assertThat(isAssigned(unassignedProject, owner)).isFalse()
    }

    @Test
    fun `nobody can start a timer on an archived project`() {
        archiveProject(organizationId, assignedProject, owner)

        listOf(owner, admin, bob).forEach { user ->
            start(assignedProject, user)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.title").value("Project is archived"))
        }

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `a non-member cannot start a timer, and is not told the organization exists`() {
        val outsider = newUser("outsider@example.com")

        start(assignedProject, outsider).andExpect(status().isNotFound)

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `starting requires authentication and a valid project id`() {
        postJson(timerPath(), """{"projectId":"$assignedProject"}""").andExpect(status().isUnauthorized)

        listOf("""{}""", """{"projectId":null}""", """{"projectId":"not-a-uuid"}""")
            .forEach { postJson(timerPath(), it, bob.accessToken).andExpect(status().isBadRequest) }

        postJson(timerPath(), """{"projectId":"${UUID.randomUUID()}"}""", bob.accessToken)
            .andExpect(status().isNotFound)

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `a start request cannot dictate the start time or the duration`() {
        val faked = Instant.parse("2020-01-01T00:00:00Z")
        val before = Instant.now()

        val body = postJson(
            timerPath(),
            """{"projectId":"$assignedProject","startedAt":"$faked","durationSeconds":99999,"endedAt":"$faked"}""",
            bob.accessToken,
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        val entry = timeEntryRepository.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow()

        assertThat(entry.startedAt).isBetween(before, Instant.now())
        assertThat(entry.endedAt).isNull()
        assertThat(entry.durationSeconds).isNull()
    }

    // --- one timer at a time ---------------------------------------------------------------

    @Test
    fun `a second timer in the same organization is rejected rather than replacing the first`() {
        val first = startTimer(organizationId, assignedProject, bob)

        start(assignedProject, bob)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Timer already running"))

        // The first timer is untouched - nothing was stopped implicitly.
        val entry = timeEntryRepository.findById(first).orElseThrow()
        assertThat(entry.endedAt).isNull()
        assertThat(timeEntryRepository.count()).isEqualTo(1)
    }

    @Test
    fun `stopping the first timer frees the slot for a second`() {
        val first = startTimer(organizationId, assignedProject, bob)
        stop(first, bob).andExpect(status().isOk)

        start(assignedProject, bob).andExpect(status().isCreated)

        assertThat(timeEntryRepository.count()).isEqualTo(2)
    }

    @Test
    fun `the limit is per organization, so timers in different organizations run independently`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")
        addMember(otherOrganization, bob, OrganizationRole.MEMBER)
        val otherProject = createProject(otherOrganization, otherOwner, "Other Project")
        assignToProject(otherOrganization, otherProject, bob, otherOwner)

        startTimer(organizationId, assignedProject, bob)
        startTimer(otherOrganization, otherProject, bob)

        // Someone consulting for two companies can legitimately be on the clock at both.
        getRequest(timerPath(), bob.accessToken).andExpect(status().isOk)
        getRequest("/api/organizations/$otherOrganization/time-entries/timer", bob.accessToken)
            .andExpect(status().isOk)
        assertThat(timeEntryRepository.count()).isEqualTo(2)
    }

    @Test
    fun `two users in the same organization can each run a timer`() {
        startTimer(organizationId, assignedProject, bob)
        startTimer(organizationId, assignedProject, owner)

        assertThat(timeEntryRepository.count()).isEqualTo(2)
    }

    // --- the running timer -------------------------------------------------------------------

    @Test
    fun `the running timer is returned, and its elapsed time is computed from the server clock`() {
        val timerId = startTimer(organizationId, assignedProject, bob, description = "Implement authentication")

        getRequest(timerPath(), bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(timerId.toString()))
            .andExpect(jsonPath("$.running").value(true))
            .andExpect(jsonPath("$.endedAt").doesNotExist())
            .andExpect(jsonPath("$.description").value("Implement authentication"))
            .andExpect(jsonPath("$.durationSeconds").isNumber)

        // Nothing is written back while it runs: the row keeps a null duration and the
        // elapsed value in the response is arithmetic, not state.
        assertThat(timeEntryRepository.findById(timerId).orElseThrow().durationSeconds).isNull()
    }

    @Test
    fun `the running timer survives the client going away entirely`() {
        val timerId = startTimer(organizationId, assignedProject, bob)

        // A fresh sign-in stands in for a reopened browser on another device: the client
        // holds no state at all, and the server still knows.
        val reopened = login(bob.email)

        getRequest(timerPath(), reopened.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(timerId.toString()))
            .andExpect(jsonPath("$.running").value(true))
    }

    @Test
    fun `no running timer is an empty response, not an error`() {
        getRequest(timerPath(), bob.accessToken).andExpect(status().isNoContent)

        // ...and a stopped one does not count.
        val timerId = startTimer(organizationId, assignedProject, bob)
        stop(timerId, bob).andExpect(status().isOk)
        getRequest(timerPath(), bob.accessToken).andExpect(status().isNoContent)
    }

    @Test
    fun `each user sees only their own running timer`() {
        val bobsTimer = startTimer(organizationId, assignedProject, bob)
        val ownersTimer = startTimer(organizationId, assignedProject, owner)

        getRequest(timerPath(), bob.accessToken)
            .andExpect(jsonPath("$.id").value(bobsTimer.toString()))
        getRequest(timerPath(), owner.accessToken)
            .andExpect(jsonPath("$.id").value(ownersTimer.toString()))

        // Not even an administrator sees someone else's timer here - this endpoint answers
        // "what am I doing", not "what is everyone doing".
        getRequest(timerPath(), admin.accessToken).andExpect(status().isNoContent)
    }

    // --- stopping --------------------------------------------------------------------------

    @Test
    fun `a user can stop their own timer and the duration is calculated server-side`() {
        val timerId = startTimer(organizationId, assignedProject, bob)

        // Backdate the start so the elapsed time is unambiguous rather than a race with
        // the test's own execution speed.
        val startedAt = Instant.now().minus(Duration.ofMinutes(90))
        timeEntryRepository.findById(timerId).orElseThrow()
            .also { it.startedAt = startedAt }
            .let { timeEntryRepository.saveAndFlush(it) }

        stop(timerId, bob)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.endedAt").isNotEmpty)

        val entry = timeEntryRepository.findById(timerId).orElseThrow()
        assertThat(entry.endedAt).isNotNull()
        assertThat(entry.durationSeconds)
            .isEqualTo(Duration.between(entry.startedAt, entry.endedAt).seconds)
        assertThat(entry.durationSeconds).isBetween(5400L, 5460L)
    }

    @Test
    fun `a stop request cannot dictate the end time`() {
        val timerId = startTimer(organizationId, assignedProject, bob)
        val faked = Instant.parse("2030-01-01T00:00:00Z")
        val before = Instant.now()

        postJson(
            "/api/organizations/$organizationId/time-entries/$timerId/stop",
            """{"endedAt":"$faked","durationSeconds":99999}""",
            bob.accessToken,
        ).andExpect(status().isOk)

        val entry = timeEntryRepository.findById(timerId).orElseThrow()
        assertThat(entry.endedAt).isBetween(before, Instant.now())
        assertThat(entry.durationSeconds).isLessThan(60)
    }

    @Test
    fun `nobody else can stop a running timer, administrator or not`() {
        val timerId = startTimer(organizationId, assignedProject, bob)

        // An administrator can see the entry, so this is a considered refusal rather than
        // a "no such thing" - stopping asserts what someone is doing right now, and only
        // they are in a position to say.
        stop(timerId, owner).andExpect(status().isForbidden)
        stop(timerId, admin).andExpect(status().isForbidden)

        // Another member cannot even see it.
        val sarah = newUser("sarah@example.com")
        addMember(organizationId, sarah, OrganizationRole.MEMBER)
        stop(timerId, sarah).andExpect(status().isNotFound)

        assertThat(timeEntryRepository.findById(timerId).orElseThrow().endedAt).isNull()
    }

    @Test
    fun `stopping twice is a conflict and does not corrupt the entry`() {
        val timerId = startTimer(organizationId, assignedProject, bob)

        stop(timerId, bob).andExpect(status().isOk)
        val afterFirstStop = timeEntryRepository.findById(timerId).orElseThrow()
        val endedAt = afterFirstStop.endedAt
        val duration = afterFirstStop.durationSeconds

        stop(timerId, bob)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Time entry is not running"))

        val afterSecondStop = timeEntryRepository.findById(timerId).orElseThrow()
        assertThat(afterSecondStop.endedAt).isEqualTo(endedAt)
        assertThat(afterSecondStop.durationSeconds).isEqualTo(duration)
    }

    @Test
    fun `stopping a manual entry is a conflict - it was never running`() {
        val manual = createTimeEntry(
            organizationId,
            assignedProject,
            bob,
            startedAt = Instant.now().minus(Duration.ofHours(3)),
            endedAt = Instant.now().minus(Duration.ofHours(1)),
        )

        stop(manual, bob).andExpect(status().isConflict)
    }

    @Test
    fun `stopping something that does not exist is a not found`() {
        stop(UUID.randomUUID(), bob).andExpect(status().isNotFound)
    }

    // --- archived projects and running timers -------------------------------------------------

    @Test
    fun `a timer already running against a project that gets archived keeps running`() {
        val timerId = startTimer(organizationId, assignedProject, bob)

        archiveProject(organizationId, assignedProject, owner)

        // The documented behaviour: archiving stops new time being tracked, it does not
        // reach in and end work already in progress or destroy it.
        getRequest(timerPath(), bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(timerId.toString()))
            .andExpect(jsonPath("$.running").value(true))

        stop(timerId, bob).andExpect(status().isOk)
        assertThat(timeEntryRepository.findById(timerId).orElseThrow().durationSeconds).isNotNull()

        // ...but no new timer against it.
        start(assignedProject, bob).andExpect(status().isConflict)
    }
}
