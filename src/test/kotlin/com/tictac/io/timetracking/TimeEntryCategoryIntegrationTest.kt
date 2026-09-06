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
 * Time entries with and without a category.
 *
 * The category is optional throughout - a project with none is valid and tracking straight
 * against a project must keep working - and when it is present it must belong to the entry's
 * own project. That consistency is asserted from both sides here: the application refuses the
 * mismatch, and the composite foreign key from V11 refuses it again underneath.
 */
@DisplayName("Time entries and project categories")
class TimeEntryCategoryIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var bob: TestUser
    private var organizationId = UUID.randomUUID()
    private var website = UUID.randomUUID()
    private var mobile = UUID.randomUUID()
    private var development = UUID.randomUUID()
    private var meetings = UUID.randomUUID()
    private var research = UUID.randomUUID()

    private val nineAm: Instant = Instant.parse("2026-09-01T09:00:00Z")
    private val elevenAm: Instant = Instant.parse("2026-09-01T11:00:00Z")

    @BeforeEach
    fun createOrganizationWithCategories() {
        owner = newUser("owner@example.com")
        bob = newUser("bob@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, bob, OrganizationRole.MEMBER)

        website = createProject(organizationId, owner, "Website Redesign")
        mobile = createProject(organizationId, owner, "Mobile App")
        assignToProject(organizationId, website, bob, owner)
        assignToProject(organizationId, mobile, bob, owner)

        development = createCategory(organizationId, website, owner, "Development")
        meetings = createCategory(organizationId, website, owner, "Meetings")
        research = createCategory(organizationId, mobile, owner, "Research")
    }

    private fun entriesPath() = "/api/organizations/$organizationId/time-entries"

    private fun timerPath() = "${entriesPath()}/timer"

    private fun entryPath(id: UUID) = "${entriesPath()}/$id"

    private fun manual(body: String, user: TestUser = bob) = postJson(entriesPath(), body, user.accessToken)

    private fun timer(body: String, user: TestUser = bob) = postJson(timerPath(), body, user.accessToken)

    // --- without a category ----------------------------------------------------------------

    @Test
    fun `a manual entry can be recorded straight against a project`() {
        manual(
            """{"projectId":"$website","title":"General project work","startedAt":"$nineAm","endedAt":"$elevenAm"}""",
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("General project work"))
            .andExpect(jsonPath("$.projectCategoryId").doesNotExist())
            .andExpect(jsonPath("$.projectCategoryName").doesNotExist())
            .andExpect(jsonPath("$.description").doesNotExist())
    }

    @Test
    fun `an explicit null category is the same as leaving it out`() {
        manual(
            """{"projectId":"$website","projectCategoryId":null,"title":"General project work",""" +
                """"description":null,"startedAt":"$nineAm","endedAt":"$elevenAm"}""",
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.projectCategoryId").doesNotExist())
    }

    @Test
    fun `a timer can be started without a category`() {
        timer("""{"projectId":"$website","title":"General project work"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.projectCategoryId").doesNotExist())
            .andExpect(jsonPath("$.running").value(true))
    }

    @Test
    fun `a project with no categories at all is fully usable`() {
        val bare = createProject(organizationId, owner, "Internal Tools")
        assignToProject(organizationId, bare, bob, owner)
        assertThat(projectCategoryRepository.countByProjectId(bare)).isZero()

        timer("""{"projectId":"$bare","title":"Tinkering"}""").andExpect(status().isCreated)
    }

    @Test
    fun `an uncategorised entry can be edited without acquiring a category`() {
        val id = createTimeEntry(organizationId, website, bob, nineAm, elevenAm, title = "Original")

        patchJson(entryPath(id), """{"title":"Corrected","billable":true}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Corrected"))
            .andExpect(jsonPath("$.projectCategoryId").doesNotExist())
    }

    // --- with a category --------------------------------------------------------------------

    @Test
    fun `a manual entry can name a category of its own project`() {
        manual(
            """{"projectId":"$website","projectCategoryId":"$development","title":"Implement OAuth",""" +
                """"description":"Added Google OAuth authentication","startedAt":"$nineAm",""" +
                """"endedAt":"$elevenAm","billable":true}""",
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.projectCategoryId").value(development.toString()))
            .andExpect(jsonPath("$.projectCategoryName").value("Development"))
            .andExpect(jsonPath("$.title").value("Implement OAuth"))
            .andExpect(jsonPath("$.description").value("Added Google OAuth authentication"))
            .andExpect(jsonPath("$.durationSeconds").value(7200))
    }

    @Test
    fun `a timer can name a category of its own project`() {
        timer("""{"projectId":"$website","projectCategoryId":"$meetings","title":"Client design review"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.projectCategoryId").value(meetings.toString()))
            .andExpect(jsonPath("$.projectCategoryName").value("Meetings"))
    }

    @Test
    fun `a category from another project in the same organization is rejected`() {
        // The critical invariant: research belongs to Mobile App, not Website Redesign.
        manual(
            """{"projectId":"$website","projectCategoryId":"$research","title":"Work",""" +
                """"startedAt":"$nineAm","endedAt":"$elevenAm"}""",
        ).andExpect(status().isNotFound)

        timer("""{"projectId":"$website","projectCategoryId":"$research","title":"Work"}""")
            .andExpect(status().isNotFound)

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `a category that does not exist is rejected`() {
        manual(
            """{"projectId":"$website","projectCategoryId":"${UUID.randomUUID()}","title":"Work",""" +
                """"startedAt":"$nineAm","endedAt":"$elevenAm"}""",
        ).andExpect(status().isNotFound)

        timer("""{"projectId":"$website","projectCategoryId":"${UUID.randomUUID()}","title":"Work"}""")
            .andExpect(status().isNotFound)
    }

    @Test
    fun `an archived category cannot be used for new work`() {
        archiveCategory(organizationId, website, development, owner)

        manual(
            """{"projectId":"$website","projectCategoryId":"$development","title":"Work",""" +
                """"startedAt":"$nineAm","endedAt":"$elevenAm"}""",
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Category is archived"))

        timer("""{"projectId":"$website","projectCategoryId":"$development","title":"Work"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Category is archived"))

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `the database refuses a category from the wrong project even with the service out of the way`() {
        // The composite foreign key on (project_category_id, project_id) is what makes the
        // consistency invariant a guarantee rather than an application promise.
        org.assertj.core.api.Assertions.assertThatThrownBy {
            timeEntryRepository.saveAndFlush(
                TimeEntry(
                    organizationId = organizationId,
                    projectId = website,
                    projectCategoryId = research,
                    userId = bob.id,
                    startedAt = nineAm,
                    title = "Smuggled",
                ).apply {
                    endedAt = elevenAm
                    recalculateDuration()
                },
            )
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)

        assertThat(timeEntryRepository.count()).isZero()
    }

    // --- historical entries keep their category ------------------------------------------------

    @Test
    fun `archiving a category leaves historical entries alone`() {
        val id = createTimeEntry(
            organizationId,
            website,
            bob,
            nineAm,
            elevenAm,
            title = "Implement OAuth",
            categoryId = development,
        )

        archiveCategory(organizationId, website, development, owner)

        val entry = timeEntryRepository.findById(id).orElseThrow()
        assertThat(entry.projectCategoryId).isEqualTo(development)
        assertThat(entry.durationSeconds).isEqualTo(7200)

        // Still readable, and still naming the retired category.
        getRequest(entryPath(id), bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectCategoryId").value(development.toString()))
            .andExpect(jsonPath("$.projectCategoryName").value("Development"))
    }

    @Test
    fun `a historical entry can be edited while keeping its archived category`() {
        val id = createTimeEntry(
            organizationId,
            website,
            bob,
            nineAm,
            elevenAm,
            title = "Original",
            categoryId = development,
        )
        archiveCategory(organizationId, website, development, owner)

        patchJson(entryPath(id), """{"title":"Corrected"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Corrected"))
            .andExpect(jsonPath("$.projectCategoryId").value(development.toString()))
    }

    @Test
    fun `a historical entry can be moved onto an archived category`() {
        val id = createTimeEntry(organizationId, website, bob, nineAm, elevenAm, title = "Work")
        archiveCategory(organizationId, website, meetings, owner)

        // The documented distinction: new work needs an active category, corrections do not.
        patchJson(entryPath(id), """{"projectCategoryId":"$meetings"}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectCategoryId").value(meetings.toString()))
    }

    // --- editing the category -------------------------------------------------------------------

    @Test
    fun `the category can be changed within the same project`() {
        val id = createTimeEntry(organizationId, website, bob, nineAm, elevenAm, categoryId = development)

        patchJson(entryPath(id), """{"projectCategoryId":"$meetings"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectCategoryId").value(meetings.toString()))
            .andExpect(jsonPath("$.projectCategoryName").value("Meetings"))
    }

    @Test
    fun `the category can be removed`() {
        val id = createTimeEntry(organizationId, website, bob, nineAm, elevenAm, categoryId = development)

        // Absent and null both mean "leave it alone", so removing needs to be said explicitly.
        patchJson(entryPath(id), """{"projectCategoryId":null}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectCategoryId").value(development.toString()))

        patchJson(entryPath(id), """{"clearProjectCategory":true}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectCategoryId").doesNotExist())

        assertThat(timeEntryRepository.findById(id).orElseThrow().projectCategoryId).isNull()
    }

    @Test
    fun `moving to another project and its category at once works`() {
        val id = createTimeEntry(organizationId, website, bob, nineAm, elevenAm, categoryId = development)

        patchJson(
            entryPath(id),
            """{"projectId":"$mobile","projectCategoryId":"$research"}""",
            bob.accessToken,
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectId").value(mobile.toString()))
            .andExpect(jsonPath("$.projectCategoryId").value(research.toString()))
    }

    @Test
    fun `moving to another project without a category drops the old one`() {
        val id = createTimeEntry(organizationId, website, bob, nineAm, elevenAm, categoryId = development)

        // The old category cannot follow, and leaving it would be a state the foreign key
        // refuses - so it goes, as a defined outcome rather than a 500.
        patchJson(entryPath(id), """{"projectId":"$mobile"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectId").value(mobile.toString()))
            .andExpect(jsonPath("$.projectCategoryId").doesNotExist())
    }

    @Test
    fun `a project and category that do not belong together are rejected`() {
        val id = createTimeEntry(organizationId, website, bob, nineAm, elevenAm)

        patchJson(
            entryPath(id),
            """{"projectId":"$mobile","projectCategoryId":"$development"}""",
            bob.accessToken,
        ).andExpect(status().isNotFound)

        val entry = timeEntryRepository.findById(id).orElseThrow()
        assertThat(entry.projectId).isEqualTo(website)
        assertThat(entry.projectCategoryId).isNull()
    }

    // --- the running timer keeps its selection -----------------------------------------------------

    @Test
    fun `archiving a category does not stop a timer already running against it`() {
        val timerId = startTimer(organizationId, website, bob, categoryId = development)

        archiveCategory(organizationId, website, development, owner)

        // Allowed to continue, and stoppable - archiving governs new work, not work in flight.
        getRequest(timerPath(), bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(timerId.toString()))
            .andExpect(jsonPath("$.running").value(true))
            .andExpect(jsonPath("$.projectCategoryId").value(development.toString()))

        postJson("${entriesPath()}/$timerId/stop", "", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.running").value(false))

        assertThat(timeEntryRepository.findById(timerId).orElseThrow().durationSeconds).isNotNull()

        // ...but no new timer against it.
        timer("""{"projectId":"$website","projectCategoryId":"$development","title":"Work"}""")
            .andExpect(status().isConflict)
    }

    // --- filtering ------------------------------------------------------------------------------------

    @Test
    fun `entries can be filtered by category`() {
        createTimeEntry(organizationId, website, bob, nineAm, elevenAm, categoryId = development)
        createTimeEntry(organizationId, website, bob, nineAm, elevenAm, categoryId = meetings)
        createTimeEntry(organizationId, website, bob, nineAm, elevenAm)

        getRequest("${entriesPath()}?projectCategoryId=$development", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(1)))
            .andExpect(jsonPath("$.content[0].projectCategoryName").value("Development"))

        getRequest("${entriesPath()}?projectId=$website", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(3)))
    }

    @Test
    fun `filtering by a category from another organization returns nothing`() {
        createTimeEntry(organizationId, website, bob, nineAm, elevenAm, categoryId = development)

        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")
        val otherProject = createProject(otherOrganization, otherOwner, "Other Project")
        val otherCategory = createCategory(otherOrganization, otherProject, otherOwner, "Development")

        // The organization predicate is unconditional, so a foreign category id matches
        // nothing rather than reaching across.
        getRequest("${entriesPath()}?projectCategoryId=$otherCategory", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content", hasSize<Any>(0)))
    }
}
