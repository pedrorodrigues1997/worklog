package com.tictac.io.timetracking

import com.tictac.io.organization.OrganizationRole
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Time entries are historical business data and must outlive every membership change around
 * them. This is the rule that makes the feature trustworthy: a company's record of twelve
 * hours worked cannot evaporate because someone tidied up a project roster.
 *
 * The guarantee comes from two places, and both are checked here. `time_entries` has no
 * foreign key to `project_members` or `organization_members`, so no cascade can reach it;
 * and its own foreign keys to organizations, projects and users all restrict rather than
 * cascade, so even a hard delete of a parent fails loudly instead of silently taking
 * billable history with it.
 */
@DisplayName("Historical time entries survive membership and project changes")
class TimeEntryHistoricalDataIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var alice: TestUser
    private var organizationId = UUID.randomUUID()
    private var projectId = UUID.randomUUID()
    private var entryId = UUID.randomUUID()

    private val nineAm: Instant = Instant.parse("2026-09-01T09:00:00Z")
    private val ninePm: Instant = Instant.parse("2026-09-01T21:00:00Z")

    @BeforeEach
    fun createTwelveHoursOfWork() {
        owner = newUser("owner@example.com")
        alice = newUser("alice@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, alice, OrganizationRole.MEMBER)

        projectId = createProject(organizationId, owner, "Project A")
        assignToProject(organizationId, projectId, alice, owner)

        entryId = createTimeEntry(organizationId, projectId, alice, nineAm, ninePm, description = "Twelve hours")
    }

    private fun assertTwelveHoursIntact() {
        val entry = timeEntryRepository.findById(entryId).orElseThrow()

        assertThat(entry.deletedAt).isNull()
        assertThat(entry.durationSeconds).isEqualTo(43200)
        assertThat(entry.startedAt).isEqualTo(nineAm)
        assertThat(entry.endedAt).isEqualTo(ninePm)
        assertThat(entry.userId).isEqualTo(alice.id)
        assertThat(entry.projectId).isEqualTo(projectId)
        assertThat(entry.organizationId).isEqualTo(organizationId)
    }

    @Test
    fun `removing the user from the project keeps the entry`() {
        deleteRequest(
            "/api/organizations/$organizationId/projects/$projectId/members/${alice.id}",
            owner.accessToken,
        ).andExpect(status().isNoContent)

        assertThat(isAssigned(projectId, alice)).isFalse()
        assertTwelveHoursIntact()

        // ...and it is still visible to the organization's administrators, which is what
        // makes it usable for reporting later.
        getRequest("/api/organizations/$organizationId/time-entries", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
    }

    @Test
    fun `archiving the project keeps the entry`() {
        archiveProject(organizationId, projectId, owner)

        assertTwelveHoursIntact()

        getRequest("/api/organizations/$organizationId/time-entries/$entryId", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectName").value("Project A"))
            .andExpect(jsonPath("$.durationSeconds").value(43200))
    }

    @Test
    fun `removing the user from the organization keeps the entry`() {
        // Organization removal *does* cascade to project assignments - that invariant is
        // enforced deliberately - and it must stop exactly there.
        deleteRequest(
            "/api/organizations/$organizationId/members/${alice.id}",
            owner.accessToken,
        ).andExpect(status().isNoContent)

        assertThat(roleOf(organizationId, alice)).isNull()
        assertThat(isAssigned(projectId, alice)).isFalse()
        assertTwelveHoursIntact()

        getRequest("/api/organizations/$organizationId/time-entries", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
            .andExpect(jsonPath("$.content[0].userId").value(alice.id.toString()))
    }

    @Test
    fun `closing the user's account keeps the entry`() {
        deleteRequest("/api/users/me", alice.accessToken).andExpect(status().isNoContent)

        assertTwelveHoursIntact()
        assertThat(userRepository.findById(alice.id).orElseThrow().deletedAt).isNotNull()

        // The entry still names the original user id, which is what keeps the work
        // attributable after the account is gone.
        getRequest("/api/organizations/$organizationId/time-entries", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].userId").value(alice.id.toString()))
    }

    @Test
    fun `soft-deleting the organization keeps the entry in the database`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken).andExpect(status().isNoContent)

        // Unreachable through the API, because the organization is - but not destroyed.
        getRequest("/api/organizations/$organizationId/time-entries", owner.accessToken)
            .andExpect(status().isNotFound)
        assertTwelveHoursIntact()
    }

    @Test
    fun `everything at once still leaves the twelve hours standing`() {
        archiveProject(organizationId, projectId, owner)
        deleteRequest(
            "/api/organizations/$organizationId/projects/$projectId/members/${alice.id}",
            owner.accessToken,
        ).andExpect(status().isNoContent)
        deleteRequest(
            "/api/organizations/$organizationId/members/${alice.id}",
            owner.accessToken,
        ).andExpect(status().isNoContent)

        assertTwelveHoursIntact()
    }

    @Test
    fun `no cascade path can reach a time entry through the link tables`() {
        // Stated structurally rather than through the API: deleting every membership row
        // directly, the way a cascade would, leaves the entry untouched.
        projectMemberRepository.deleteAll()
        organizationMemberRepository.deleteAll()

        assertThat(timeEntryRepository.count()).isEqualTo(1)
        assertTwelveHoursIntact()
    }
}
