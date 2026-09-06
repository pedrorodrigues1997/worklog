package com.tictac.io.timetracking

import com.jayway.jsonpath.JsonPath
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The cross-tenant matrix for time entries - the most sensitive data in the product, since
 * it is what a customer eventually invoices from.
 *
 * User A is the OWNER of organization A throughout: fully authenticated, and an
 * administrator of a real tenant. Two attack shapes are covered on every endpoint, and the
 * second is the one that matters:
 *
 * 1. addressing organization B directly - refused by `OrganizationAccess`;
 * 2. addressing organization *A* while passing organization B's entry or project id - which
 *    would succeed against any implementation that resolves by id alone.
 */
@DisplayName("Tenant isolation: time entries cannot be reached across organizations")
class TimeEntryTenantIsolationIntegrationTest : OrganizationApiTest() {

    private lateinit var userA: TestUser
    private lateinit var userB: TestUser
    private var organizationA = UUID.randomUUID()
    private var organizationB = UUID.randomUUID()
    private var projectA = UUID.randomUUID()
    private var projectB = UUID.randomUUID()
    private var entryA = UUID.randomUUID()
    private var entryB = UUID.randomUUID()
    private var timerB = UUID.randomUUID()

    private val nineAm: Instant = Instant.parse("2026-09-01T09:00:00Z")
    private val elevenAm: Instant = Instant.parse("2026-09-01T11:00:00Z")

    @BeforeEach
    fun createTwoTenants() {
        userA = newUser("a@example.com")
        userB = newUser("b@example.com")

        organizationA = createOrganization(userA, "Organization A")
        organizationB = createOrganization(userB, "Organization B")

        projectA = createProject(organizationA, userA, "Project A")
        projectB = createProject(organizationB, userB, "Project B")

        entryA = createTimeEntry(organizationA, projectA, userA, nineAm, elevenAm, description = "Work in A")
        entryB = createTimeEntry(organizationB, projectB, userB, nineAm, elevenAm, description = "Work in B")
        timerB = startTimer(organizationB, projectB, userB, description = "Running in B")
    }

    /** Addressed through organization B, which user A does not belong to. */
    private fun viaB(id: UUID = entryB, suffix: String = "") =
        "/api/organizations/$organizationB/time-entries/$id$suffix"

    /** Addressed through user A's *own* organization, carrying organization B's entry id. */
    private fun viaOwn(id: UUID = entryB, suffix: String = "") =
        "/api/organizations/$organizationA/time-entries/$id$suffix"

    @Test
    fun `user A cannot read time entry B`() {
        listOf(viaB(), viaOwn()).forEach { path ->
            val body = getRequest(path, userA.accessToken)
                .andExpect(status().isNotFound)
                .andReturn().response.contentAsString

            assertThat(body).doesNotContain("Work in B")
        }
    }

    @Test
    fun `user A cannot modify time entry B`() {
        listOf(viaB(), viaOwn()).forEach { path ->
            patchJson(path, """{"description":"Taken over","billable":true}""", userA.accessToken)
                .andExpect(status().isNotFound)
        }

        val entry = timeEntryRepository.findById(entryB).orElseThrow()
        assertThat(entry.description).isEqualTo("Work in B")
        assertThat(entry.billable).isFalse()
    }

    @Test
    fun `user A cannot delete time entry B`() {
        listOf(viaB(), viaOwn()).forEach { path ->
            deleteRequest(path, userA.accessToken).andExpect(status().isNotFound)
        }

        assertThat(timeEntryRepository.findById(entryB).orElseThrow().deletedAt).isNull()
    }

    @Test
    fun `user A cannot stop timer B`() {
        listOf(viaB(timerB, "/stop"), viaOwn(timerB, "/stop")).forEach { path ->
            postJson(path, "", userA.accessToken).andExpect(status().isNotFound)
        }

        assertThat(timeEntryRepository.findById(timerB).orElseThrow().endedAt).isNull()
    }

    @Test
    fun `user A cannot use project B to create a time entry`() {
        // The mirror image, and the more interesting direction: a real project id, a real
        // organization the caller genuinely administers, and the two do not belong together.
        postJson(
            "/api/organizations/$organizationA/time-entries",
            """{"projectId":"$projectB","title":"Work","startedAt":"$nineAm","endedAt":"$elevenAm"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        postJson(
            "/api/organizations/$organizationA/time-entries/timer",
            """{"projectId":"$projectB","title":"Work"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(timeEntryRepository.count()).isEqualTo(3)
    }

    @Test
    fun `user A cannot move their own entry onto project B`() {
        patchJson(
            "/api/organizations/$organizationA/time-entries/$entryA",
            """{"projectId":"$projectB"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(timeEntryRepository.findById(entryA).orElseThrow().projectId).isEqualTo(projectA)
    }

    @Test
    fun `user A cannot list organization B's entries`() {
        val body = getRequest("/api/organizations/$organizationB/time-entries", userA.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("Work in B")

        // ...and B's entries never surface in A's own listing either.
        val ownListing = getRequest("/api/organizations/$organizationA/time-entries", userA.accessToken)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(JsonPath.read<List<String>>(ownListing, "$.content[*].id")).containsExactly(entryA.toString())
        assertThat(ownListing).doesNotContain("Work in B")
    }

    @Test
    fun `user A cannot see organization B's running timer`() {
        getRequest("/api/organizations/$organizationB/time-entries/timer", userA.accessToken)
            .andExpect(status().isNotFound)

        getRequest("/api/organizations/$organizationA/time-entries/timer", userA.accessToken)
            .andExpect(status().isNoContent)
    }

    @Test
    fun `a foreign entry id is indistinguishable from one that does not exist`() {
        val foreign = getRequest(viaOwn(), userA.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        val imaginary = getRequest(viaOwn(UUID.randomUUID()), userA.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        listOf("$.status", "$.title", "$.detail").forEach { field ->
            assertThat(JsonPath.read<Any>(foreign, field)).isEqualTo(JsonPath.read<Any>(imaginary, field))
        }
    }

    @Test
    fun `organization B's own people are unaffected throughout`() {
        getRequest(viaB(), userB.accessToken).andExpect(status().isOk)
        getRequest("/api/organizations/$organizationB/time-entries/timer", userB.accessToken)
            .andExpect(status().isOk)
        postJson(viaB(timerB, "/stop"), "", userB.accessToken).andExpect(status().isOk)
    }

    @Test
    fun `belonging to both organizations keeps the two sets of entries apart`() {
        // The subtler case: a legitimate member of both companies must still not see one
        // company's work through the other's URL.
        addMember(organizationB, userA, com.tictac.io.organization.OrganizationRole.MEMBER)

        getRequest(viaOwn(), userA.accessToken).andExpect(status().isNotFound)

        val inB = getRequest("/api/organizations/$organizationB/time-entries", userA.accessToken)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        // A MEMBER of B, so only their own entries there - which is none.
        assertThat(JsonPath.read<List<String>>(inB, "$.content[*].id")).isEmpty()

        // ...and a timer in each organization stays separate.
        assignToProject(organizationB, projectB, userA, userB)
        startTimer(organizationA, projectA, userA)
        startTimer(organizationB, projectB, userA)
        assertThat(
            timeEntryRepository
                .findByOrganizationIdAndUserIdAndEndedAtIsNullAndDeletedAtIsNull(organizationA, userA.id)
                ?.organizationId,
        ).isEqualTo(organizationA)
    }

    @Test
    fun `historical entries survive the caller being added to and removed from a project`() {
        val duration = timeEntryRepository.findById(entryA).orElseThrow().durationSeconds

        assignToProject(organizationA, projectA, userA, userA)
        deleteRequest(
            "/api/organizations/$organizationA/projects/$projectA/members/${userA.id}",
            userA.accessToken,
        ).andExpect(status().isNoContent)

        assertThat(timeEntryRepository.findById(entryA).orElseThrow().durationSeconds).isEqualTo(duration)
    }

    private fun Instant.plusHours(hours: Long): Instant = plus(Duration.ofHours(hours))
}
