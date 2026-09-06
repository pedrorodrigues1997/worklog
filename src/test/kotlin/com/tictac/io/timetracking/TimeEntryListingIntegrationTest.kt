package com.tictac.io.timetracking

import com.jayway.jsonpath.JsonPath
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

@DisplayName("Time entry listing: scope, filters and pagination")
class TimeEntryListingIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var bob: TestUser
    private var organizationId = UUID.randomUUID()
    private var website = UUID.randomUUID()
    private var mobile = UUID.randomUUID()

    private val day: Instant = Instant.parse("2026-09-01T09:00:00Z")

    @BeforeEach
    fun createOrganizationWithEntries() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        bob = newUser("bob@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, bob, OrganizationRole.MEMBER)

        website = createProject(organizationId, owner, "Website Redesign")
        mobile = createProject(organizationId, owner, "Mobile App")
        assignToProject(organizationId, website, bob, owner)
        assignToProject(organizationId, mobile, bob, owner)
    }

    private fun entriesPath() = "/api/organizations/$organizationId/time-entries"

    private fun entry(
        user: TestUser,
        project: UUID,
        startOffsetHours: Long,
        billable: Boolean = false,
    ): UUID = createTimeEntry(
        organizationId,
        project,
        user,
        startedAt = day.plus(Duration.ofHours(startOffsetHours)),
        endedAt = day.plus(Duration.ofHours(startOffsetHours + 1)),
        billable = billable,
    )

    private fun idsFrom(body: String): List<String> = JsonPath.read(body, "$.content[*].id")

    private fun list(user: TestUser, query: String = "") =
        getRequest(entriesPath() + query, user.accessToken)

    // --- scope ------------------------------------------------------------------------

    @Test
    fun `a member sees only their own entries`() {
        val bobs = entry(bob, website, 0)
        entry(owner, website, 1)
        entry(admin, website, 2)

        val body = list(bob)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andReturn().response.contentAsString

        assertThat(idsFrom(body)).containsExactly(bobs.toString())
    }

    @Test
    fun `an owner and an admin see the organization's entries`() {
        entry(bob, website, 0)
        entry(owner, website, 1)
        entry(admin, website, 2)

        listOf(owner, admin).forEach { user ->
            list(user)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content", hasSize<Any>(3)))
                .andExpect(jsonPath("$.totalElements").value(3))
        }
    }

    @Test
    fun `a member asking for someone else's entries gets an empty page, not a leak`() {
        entry(owner, website, 0)
        entry(bob, website, 1)

        // The member scope is a predicate ANDed with the requested filter, so this needs no
        // special case and cannot be talked past.
        list(bob, "?userId=${owner.id}")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(0)))
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    @Test
    fun `a non-member cannot list at all`() {
        entry(bob, website, 0)

        list(newUser("outsider@example.com")).andExpect(status().isNotFound)
        getRequest(entriesPath()).andExpect(status().isUnauthorized)
    }

    // --- filters ----------------------------------------------------------------------

    @Test
    fun `the project filter works`() {
        val onWebsite = entry(bob, website, 0)
        entry(bob, mobile, 1)

        val body = list(bob, "?projectId=$website")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
            .andReturn().response.contentAsString

        assertThat(idsFrom(body)).containsExactly(onWebsite.toString())
    }

    @Test
    fun `the user filter works for an administrator`() {
        val bobs = entry(bob, website, 0)
        entry(owner, website, 1)

        val body = list(owner, "?userId=${bob.id}")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
            .andReturn().response.contentAsString

        assertThat(idsFrom(body)).containsExactly(bobs.toString())
    }

    @Test
    fun `the date filters work, with an inclusive start and an exclusive end`() {
        val first = entry(bob, website, 0) // 09:00
        val second = entry(bob, website, 3) // 12:00
        entry(bob, website, 6) // 15:00

        val body = list(bob, "?from=${day}&to=${day.plus(Duration.ofHours(6))}")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(2)))
            .andReturn().response.contentAsString

        // 09:00 is included, 15:00 is not - so consecutive periods tile without
        // double-counting a boundary entry.
        assertThat(idsFrom(body)).containsExactlyInAnyOrder(first.toString(), second.toString())
    }

    @Test
    fun `the billable filter works`() {
        val billable = entry(bob, website, 0, billable = true)
        val free = entry(bob, website, 1, billable = false)

        assertThat(idsFrom(list(bob, "?billable=true").andReturn().response.contentAsString))
            .containsExactly(billable.toString())
        assertThat(idsFrom(list(bob, "?billable=false").andReturn().response.contentAsString))
            .containsExactly(free.toString())
    }

    @Test
    fun `filters combine`() {
        val wanted = entry(bob, website, 2, billable = true)
        entry(bob, website, 2, billable = false)
        entry(bob, mobile, 2, billable = true)
        entry(owner, website, 2, billable = true)

        val body = list(owner, "?projectId=$website&userId=${bob.id}&billable=true&from=$day")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
            .andReturn().response.contentAsString

        assertThat(idsFrom(body)).containsExactly(wanted.toString())
    }

    @Test
    fun `a deleted entry drops out of the listing`() {
        val kept = entry(bob, website, 0)
        val deleted = entry(bob, website, 1)
        deleteRequest("${entriesPath()}/$deleted", bob.accessToken).andExpect(status().isNoContent)

        val body = list(bob)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
            .andReturn().response.contentAsString

        assertThat(idsFrom(body)).containsExactly(kept.toString())
    }

    @Test
    fun `a running timer appears in the listing with its elapsed time`() {
        startTimer(organizationId, website, bob)

        list(bob)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
            .andExpect(jsonPath("$.content[0].running").value(true))
            .andExpect(jsonPath("$.content[0].endedAt").doesNotExist())
            .andExpect(jsonPath("$.content[0].durationSeconds").isNumber)
    }

    // --- ordering and pagination ---------------------------------------------------------

    @Test
    fun `entries come back newest first`() {
        val oldest = entry(bob, website, 0)
        val newest = entry(bob, website, 5)
        val middle = entry(bob, website, 2)

        val body = list(bob).andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(idsFrom(body))
            .containsExactly(newest.toString(), middle.toString(), oldest.toString())
    }

    @Test
    fun `pagination walks the whole set without repeating or dropping anything`() {
        val created = (0 until 7).map { entry(bob, website, it.toLong()) }.map { it.toString() }

        val firstPage = list(bob, "?page=0&size=3")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(3)))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(3))
            .andExpect(jsonPath("$.totalElements").value(7))
            .andExpect(jsonPath("$.totalPages").value(3))
            .andReturn().response.contentAsString

        val secondPage = list(bob, "?page=1&size=3").andReturn().response.contentAsString
        val thirdPage = list(bob, "?page=2&size=3")
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
            .andReturn().response.contentAsString

        val walked = idsFrom(firstPage) + idsFrom(secondPage) + idsFrom(thirdPage)
        assertThat(walked).containsExactlyInAnyOrderElementsOf(created)
        assertThat(walked).doesNotHaveDuplicates()
    }

    @Test
    fun `entries sharing a timestamp still paginate deterministically`() {
        // Without the id tiebreaker, rows with equal started_at could shuffle between pages
        // and a client would see one twice and another never.
        val sameInstant = (0 until 6).map {
            createTimeEntry(organizationId, website, bob, day, day.plus(Duration.ofHours(1)))
        }.map { it.toString() }

        val walked = (0 until 3).flatMap { page ->
            idsFrom(list(bob, "?page=$page&size=2").andReturn().response.contentAsString)
        }

        assertThat(walked).doesNotHaveDuplicates()
        assertThat(walked).containsExactlyInAnyOrderElementsOf(sameInstant)

        // ...and the same request twice gives the same answer.
        assertThat(idsFrom(list(bob, "?page=0&size=2").andReturn().response.contentAsString))
            .isEqualTo(idsFrom(list(bob, "?page=0&size=2").andReturn().response.contentAsString))
    }

    @Test
    fun `the page size is capped and nonsense values are clamped`() {
        repeat(3) { entry(bob, website, it.toLong()) }

        list(bob, "?size=100000")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(PageResponse.MAX_PAGE_SIZE))

        list(bob, "?size=0&page=-3")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.page").value(0))
    }

    @Test
    fun `the default page size applies when none is given`() {
        entry(bob, website, 0)

        list(bob)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(PageResponse.DEFAULT_PAGE_SIZE))
            .andExpect(jsonPath("$.page").value(0))
    }

    @Test
    fun `a page beyond the end is empty rather than an error`() {
        entry(bob, website, 0)

        list(bob, "?page=99&size=10")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(0)))
            .andExpect(jsonPath("$.totalElements").value(1))
    }
}
