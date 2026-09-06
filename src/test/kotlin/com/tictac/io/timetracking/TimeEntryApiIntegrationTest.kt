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

@DisplayName("Time entries: manual creation, editing and deletion")
class TimeEntryApiIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var bob: TestUser
    private lateinit var sarah: TestUser
    private var organizationId = UUID.randomUUID()
    private var projectId = UUID.randomUUID()
    private var secondProject = UUID.randomUUID()

    private val nineAm: Instant = Instant.parse("2026-09-01T09:00:00Z")
    private val elevenAm: Instant = Instant.parse("2026-09-01T11:00:00Z")

    @BeforeEach
    fun createOrganizationWithProjects() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        bob = newUser("bob@example.com")
        sarah = newUser("sarah@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, bob, OrganizationRole.MEMBER)
        addMember(organizationId, sarah, OrganizationRole.MEMBER)

        projectId = createProject(organizationId, owner, "Website Redesign")
        secondProject = createProject(organizationId, owner, "Mobile App")
        assignToProject(organizationId, projectId, bob, owner)
        assignToProject(organizationId, secondProject, bob, owner)
    }

    private fun entriesPath() = "/api/organizations/$organizationId/time-entries"

    private fun entryPath(id: UUID) = "${entriesPath()}/$id"

    // --- manual creation -------------------------------------------------------------------

    @Test
    fun `a valid manual entry succeeds and the duration is calculated server-side`() {
        val body = postJson(
            entriesPath(),
            """{"projectId":"$projectId","description":"Client meeting","startedAt":"$nineAm","endedAt":"$elevenAm","billable":true}""",
            bob.accessToken,
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.description").value("Client meeting"))
            .andExpect(jsonPath("$.billable").value(true))
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.durationSeconds").value(7200))
            .andExpect(jsonPath("$.projectName").value("Website Redesign"))
            .andReturn().response.contentAsString

        val entry = timeEntryRepository.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow()
        assertThat(entry.startedAt).isEqualTo(nineAm)
        assertThat(entry.endedAt).isEqualTo(elevenAm)
        assertThat(entry.durationSeconds).isEqualTo(7200)
        assertThat(entry.userId).isEqualTo(bob.id)
    }

    @Test
    fun `a client-provided duration cannot override the calculated one`() {
        val body = postJson(
            entriesPath(),
            """{"projectId":"$projectId","startedAt":"$nineAm","endedAt":"$elevenAm","durationSeconds":99999}""",
            bob.accessToken,
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        // There is no duration field on the request DTO, so the value has nowhere to land.
        assertThat(JsonPath.read<Int>(body, "$.durationSeconds")).isEqualTo(7200)
        assertThat(timeEntryRepository.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow().durationSeconds)
            .isEqualTo(7200)
    }

    @Test
    fun `end must be after start`() {
        listOf(elevenAm to nineAm, nineAm to nineAm).forEach { (start, end) ->
            postJson(
                entriesPath(),
                """{"projectId":"$projectId","startedAt":"$start","endedAt":"$end"}""",
                bob.accessToken,
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.title").value("Invalid time range"))
        }

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `both timestamps are required for a manual entry`() {
        listOf(
            """{"projectId":"$projectId"}""",
            """{"projectId":"$projectId","startedAt":"$nineAm"}""",
            """{"projectId":"$projectId","endedAt":"$elevenAm"}""",
        ).forEach { postJson(entriesPath(), it, bob.accessToken).andExpect(status().isBadRequest) }

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `timestamps are accepted with any offset and stored as the same instant`() {
        // 11:00Z and 15:00+04:00 are the same moment. A Dubai client and a Lisbon client
        // describing one meeting must produce identical rows.
        val utc = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)
        val offset = postJson(
            entriesPath(),
            """{"projectId":"$projectId","startedAt":"2026-09-01T13:00:00+04:00","endedAt":"2026-09-01T15:00:00+04:00"}""",
            bob.accessToken,
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        val fromOffset = timeEntryRepository.findById(UUID.fromString(JsonPath.read(offset, "$.id"))).orElseThrow()
        assertThat(fromOffset.startedAt).isEqualTo(timeEntryRepository.findById(utc).orElseThrow().startedAt)
        assertThat(fromOffset.endedAt).isEqualTo(elevenAm)
    }

    @Test
    fun `a local time with no zone context is rejected`() {
        // "09:00" is not a moment until someone says where. Guessing is how a timesheet
        // ends up hours out.
        postJson(
            entriesPath(),
            """{"projectId":"$projectId","startedAt":"2026-09-01T09:00:00","endedAt":"2026-09-01T11:00:00"}""",
            bob.accessToken,
        ).andExpect(status().isBadRequest)

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `historical timestamps work`() {
        val lastYear = Instant.parse("2025-03-04T08:00:00Z")

        val id = createTimeEntry(organizationId, projectId, bob, lastYear, lastYear.plus(Duration.ofHours(4)))

        assertThat(timeEntryRepository.findById(id).orElseThrow().durationSeconds).isEqualTo(14400)
    }

    @Test
    fun `overlapping manual entries are accepted`() {
        // 09:00-11:00 on one project and 10:00-12:00 on another may both be true; deciding
        // which is wrong is a reporting question, not something to guess at write time.
        createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)
        createTimeEntry(
            organizationId,
            secondProject,
            bob,
            nineAm.plus(Duration.ofHours(1)),
            elevenAm.plus(Duration.ofHours(1)),
        )

        assertThat(timeEntryRepository.count()).isEqualTo(2)
    }

    @Test
    fun `a manual entry may overlap a running timer`() {
        startTimer(organizationId, projectId, bob)

        createTimeEntry(organizationId, projectId, bob, Instant.now().minus(Duration.ofHours(1)), Instant.now())

        // Only two *running* timers are forbidden; a completed entry never occupies the slot.
        assertThat(timeEntryRepository.count()).isEqualTo(2)
    }

    @Test
    fun `a member cannot create an entry on a project they are not assigned to`() {
        val hidden = createProject(organizationId, owner, "Internal Tools")

        postJson(
            entriesPath(),
            """{"projectId":"$hidden","startedAt":"$nineAm","endedAt":"$elevenAm"}""",
            bob.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `a manual entry cannot be created against an archived project`() {
        archiveProject(organizationId, projectId, owner)

        postJson(
            entriesPath(),
            """{"projectId":"$projectId","startedAt":"$nineAm","endedAt":"$elevenAm"}""",
            bob.accessToken,
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Project is archived"))
    }

    @Test
    fun `billable defaults to false when not stated`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)

        assertThat(timeEntryRepository.findById(id).orElseThrow().billable).isFalse()
    }

    @Test
    fun `creating requires authentication`() {
        postJson(entriesPath(), """{"projectId":"$projectId","startedAt":"$nineAm","endedAt":"$elevenAm"}""")
            .andExpect(status().isUnauthorized)

        assertThat(timeEntryRepository.count()).isZero()
    }

    // --- editing --------------------------------------------------------------------------

    @Test
    fun `a user can edit their own entry`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm, description = "Original")

        patchJson(entryPath(id), """{"description":"Corrected","billable":true}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.description").value("Corrected"))
            .andExpect(jsonPath("$.billable").value(true))
            .andExpect(jsonPath("$.durationSeconds").value(7200))
    }

    @Test
    fun `changing the timestamps recalculates the duration`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)

        patchJson(entryPath(id), """{"endedAt":"${nineAm.plus(Duration.ofMinutes(30))}"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.durationSeconds").value(1800))

        patchJson(entryPath(id), """{"startedAt":"${nineAm.minus(Duration.ofHours(1))}"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.durationSeconds").value(5400))

        assertThat(timeEntryRepository.findById(id).orElseThrow().durationSeconds).isEqualTo(5400)
    }

    @Test
    fun `an edit that would invert the interval is rejected and changes nothing`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)

        patchJson(entryPath(id), """{"startedAt":"${elevenAm.plus(Duration.ofHours(1))}"}""", bob.accessToken)
            .andExpect(status().isBadRequest)

        val entry = timeEntryRepository.findById(id).orElseThrow()
        assertThat(entry.startedAt).isEqualTo(nineAm)
        assertThat(entry.durationSeconds).isEqualTo(7200)
    }

    @Test
    fun `an administrator can edit a member's entry, and a member cannot edit anyone else's`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)

        patchJson(entryPath(id), """{"description":"Fixed by admin"}""", admin.accessToken)
            .andExpect(status().isOk)
        patchJson(entryPath(id), """{"description":"Fixed by owner"}""", owner.accessToken)
            .andExpect(status().isOk)

        // Another member cannot see it, let alone change it.
        patchJson(entryPath(id), """{"description":"Sneaky"}""", sarah.accessToken)
            .andExpect(status().isNotFound)

        assertThat(timeEntryRepository.findById(id).orElseThrow().description).isEqualTo("Fixed by owner")
    }

    @Test
    fun `an edit cannot move an entry to another organization or another user`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)
        val elsewhere = createOrganization(newUser("elsewhere@example.com"), "Other Company")

        patchJson(
            entryPath(id),
            """{"organizationId":"$elsewhere","userId":"${sarah.id}","description":"Moved"}""",
            owner.accessToken,
        ).andExpect(status().isOk)

        // Neither field exists on the request DTO, so there is no shape of PATCH that moves
        // billable history between tenants or reattributes it.
        val entry = timeEntryRepository.findById(id).orElseThrow()
        assertThat(entry.organizationId).isEqualTo(organizationId)
        assertThat(entry.userId).isEqualTo(bob.id)
        assertThat(entry.description).isEqualTo("Moved")
    }

    @Test
    fun `an entry can be moved to another project in the same organization`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)

        patchJson(entryPath(id), """{"projectId":"$secondProject"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectId").value(secondProject.toString()))
            .andExpect(jsonPath("$.projectName").value("Mobile App"))
    }

    @Test
    fun `a historical entry can be moved onto an archived project`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)
        archiveProject(organizationId, secondProject, owner)

        // The documented distinction: starting new time needs an active project, correcting
        // finished work does not - the right answer may be a project nobody is on any more.
        patchJson(entryPath(id), """{"projectId":"$secondProject"}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectId").value(secondProject.toString()))
    }

    @Test
    fun `an entry cannot be moved to a project the caller cannot reach`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)
        val hidden = createProject(organizationId, owner, "Internal Tools")

        // Bob is not assigned to it, so for him it does not exist.
        patchJson(entryPath(id), """{"projectId":"$hidden"}""", bob.accessToken)
            .andExpect(status().isNotFound)

        assertThat(timeEntryRepository.findById(id).orElseThrow().projectId).isEqualTo(projectId)
    }

    @Test
    fun `an absent field is left alone`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm, description = "Original")

        patchJson(entryPath(id), """{}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.description").value("Original"))
            .andExpect(jsonPath("$.durationSeconds").value(7200))
            .andExpect(jsonPath("$.billable").value(false))
    }

    @Test
    fun `editing a running timer keeps it running and its duration unset`() {
        val timerId = startTimer(organizationId, projectId, bob)

        patchJson(entryPath(timerId), """{"description":"Renamed mid-flight"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.running").value(true))

        assertThat(timeEntryRepository.findById(timerId).orElseThrow().durationSeconds).isNull()
    }

    // --- deletion ---------------------------------------------------------------------------

    @Test
    fun `a user can delete their own entry, and it is a soft delete`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)

        deleteRequest(entryPath(id), bob.accessToken).andExpect(status().isNoContent)

        // The row survives - see TimeEntryService.delete for why - but is gone from the API.
        val entry = timeEntryRepository.findById(id).orElseThrow()
        assertThat(entry.deletedAt).isNotNull()
        assertThat(entry.durationSeconds).isEqualTo(7200)
        getRequest(entryPath(id), bob.accessToken).andExpect(status().isNotFound)
    }

    @Test
    fun `an administrator can delete a member's entry, and a member cannot delete anyone else's`() {
        val first = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)
        val second = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)
        val third = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)

        deleteRequest(entryPath(first), admin.accessToken).andExpect(status().isNoContent)
        deleteRequest(entryPath(second), owner.accessToken).andExpect(status().isNoContent)
        deleteRequest(entryPath(third), sarah.accessToken).andExpect(status().isNotFound)

        assertThat(timeEntryRepository.findById(third).orElseThrow().deletedAt).isNull()
    }

    @Test
    fun `deleting a running timer frees the slot`() {
        val timerId = startTimer(organizationId, projectId, bob)

        deleteRequest(entryPath(timerId), bob.accessToken).andExpect(status().isNoContent)

        // The partial unique index excludes soft-deleted rows, so a mistaken timer does not
        // lock someone out of starting the right one.
        getRequest("${entriesPath()}/timer", bob.accessToken).andExpect(status().isNoContent)
        startTimer(organizationId, projectId, bob)
    }

    @Test
    fun `a deleted entry cannot be deleted or edited again`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)
        deleteRequest(entryPath(id), bob.accessToken).andExpect(status().isNoContent)

        deleteRequest(entryPath(id), bob.accessToken).andExpect(status().isNotFound)
        patchJson(entryPath(id), """{"description":"Zombie"}""", bob.accessToken).andExpect(status().isNotFound)
    }

    @Test
    fun `deleting requires authentication`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)

        deleteRequest(entryPath(id)).andExpect(status().isUnauthorized)

        assertThat(timeEntryRepository.findById(id).orElseThrow().deletedAt).isNull()
    }

    // --- reading one ---------------------------------------------------------------------------

    @Test
    fun `a member reads their own entry and an administrator reads anyone's`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)

        listOf(bob, admin, owner).forEach { user ->
            getRequest(entryPath(id), user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(id.toString()))
        }

        getRequest(entryPath(id), sarah.accessToken).andExpect(status().isNotFound)
    }

    @Test
    fun `an entry that does not exist is a not found, and a bad id is a bad request`() {
        getRequest(entryPath(UUID.randomUUID()), bob.accessToken).andExpect(status().isNotFound)
        getRequest("${entriesPath()}/not-a-uuid", bob.accessToken).andExpect(status().isBadRequest)
    }
}
