package com.tictac.io.timetracking

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

/**
 * The title, the description, and the ceiling on how long one entry may be.
 *
 * The title is the user-facing answer to "what are you working on?", so it is mandatory
 * everywhere time is recorded - and validated as a *domain* rule rather than by Bean
 * Validation, which is what lets it answer 422 while a missing `projectId` still answers 400.
 */
@DisplayName("Time entry title, description and duration limits")
class TimeEntryTitleIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var bob: TestUser
    private var organizationId = UUID.randomUUID()
    private var projectId = UUID.randomUUID()

    private val nineAm: Instant = Instant.parse("2026-09-01T09:00:00Z")
    private val elevenAm: Instant = Instant.parse("2026-09-01T11:00:00Z")

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        bob = newUser("bob@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, bob, OrganizationRole.MEMBER)

        projectId = createProject(organizationId, owner, "Website Redesign")
        assignToProject(organizationId, projectId, bob, owner)
    }

    private fun entriesPath() = "/api/organizations/$organizationId/time-entries"

    private fun timerPath() = "${entriesPath()}/timer"

    private fun manual(titleJson: String) =
        postJson(
            entriesPath(),
            """{"projectId":"$projectId",$titleJson"startedAt":"$nineAm","endedAt":"$elevenAm"}""",
            bob.accessToken,
        )

    private fun timer(titleJson: String) =
        postJson(timerPath(), """{"projectId":"$projectId",$titleJson}""", bob.accessToken)

    // --- the title is mandatory --------------------------------------------------------

    @Test
    fun `a manual entry without a usable title is refused`() {
        listOf("", """"title":null,""", """"title":"",""", """"title":"   ",""").forEach { titleJson ->
            manual(titleJson)
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.title").value("Invalid time entry"))
                .andExpect(jsonPath("$.detail").value("Title is required"))
        }

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `a timer without a usable title is refused`() {
        listOf(""""title":null""", """"title":""""", """"title":"   """").forEach { titleJson ->
            timer(titleJson).andExpect(status().isUnprocessableEntity)
        }

        // ...and with the field left out entirely.
        postJson(timerPath(), """{"projectId":"$projectId"}""", bob.accessToken)
            .andExpect(status().isUnprocessableEntity)

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `the title is trimmed before it is judged and before it is stored`() {
        val body = manual(""""title":"  Implement OAuth  ",""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("Implement OAuth"))
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("  Implement OAuth  ")
    }

    @Test
    fun `a title at the limit is accepted and one past it is not`() {
        manual(""""title":"${"a".repeat(TimeEntry.MAX_TITLE_LENGTH)}",""")
            .andExpect(status().isCreated)

        manual(""""title":"${"a".repeat(TimeEntry.MAX_TITLE_LENGTH + 1)}",""")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.detail").value("Title must be at most 255 characters"))

        assertThat(timeEntryRepository.count()).isEqualTo(1)
    }

    @Test
    fun `a missing project id is still a bad request, not a domain failure`() {
        // The split that keeps the two statuses meaningful: a malformed request is 400, a
        // well-formed one carrying an unusable value is 422.
        postJson(
            entriesPath(),
            """{"title":"Work","startedAt":"$nineAm","endedAt":"$elevenAm"}""",
            bob.accessToken,
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `the title cannot be cleared by an edit`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm, title = "Implement OAuth")

        listOf("""{"title":""}""", """{"title":"   "}""").forEach { body ->
            patchJson("${entriesPath()}/$id", body, bob.accessToken)
                .andExpect(status().isUnprocessableEntity)
        }

        // Absent still means "leave it alone".
        patchJson("${entriesPath()}/$id", """{"billable":true}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Implement OAuth"))

        assertThat(timeEntryRepository.findById(id).orElseThrow().title).isEqualTo("Implement OAuth")
    }

    @Test
    fun `the title can be corrected`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm, title = "Implemnt OAuth")

        patchJson("${entriesPath()}/$id", """{"title":"Implement OAuth"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Implement OAuth"))
    }

    // --- the description is optional ------------------------------------------------------

    @Test
    fun `the description is optional and blank is stored as nothing`() {
        listOf("", """"description":null,""", """"description":"",""", """"description":"   ",""")
            .forEach { descriptionJson ->
                postJson(
                    entriesPath(),
                    """{"projectId":"$projectId","title":"Work",$descriptionJson""" +
                        """"startedAt":"$nineAm","endedAt":"$elevenAm"}""",
                    bob.accessToken,
                )
                    .andExpect(status().isCreated)
                    .andExpect(jsonPath("$.description").doesNotExist())
            }

        assertThat(timeEntryRepository.findAll()).allMatch { it.description == null }
    }

    @Test
    fun `a description at the limit is accepted and one past it is not`() {
        val atLimit = "a".repeat(TimeEntry.MAX_DESCRIPTION_LENGTH)
        postJson(
            entriesPath(),
            """{"projectId":"$projectId","title":"Work","description":"$atLimit",""" +
                """"startedAt":"$nineAm","endedAt":"$elevenAm"}""",
            bob.accessToken,
        ).andExpect(status().isCreated)

        val tooLong = "a".repeat(TimeEntry.MAX_DESCRIPTION_LENGTH + 1)
        postJson(
            entriesPath(),
            """{"projectId":"$projectId","title":"Work","description":"$tooLong",""" +
                """"startedAt":"$nineAm","endedAt":"$elevenAm"}""",
            bob.accessToken,
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.detail").value("Description must be at most 5000 characters"))

        assertThat(timeEntryRepository.count()).isEqualTo(1)
    }

    @Test
    fun `a description can be cleared with an empty string`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm, description = "Notes")

        patchJson("${entriesPath()}/$id", """{"description":""}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.description").doesNotExist())

        assertThat(timeEntryRepository.findById(id).orElseThrow().description).isNull()
    }

    // --- the duration ceiling ---------------------------------------------------------------

    @Test
    fun `a manual entry cannot span more than a day`() {
        val justUnder = nineAm.plus(TimeEntry.MAX_DURATION).minusSeconds(1)
        val justOver = nineAm.plus(TimeEntry.MAX_DURATION).plusSeconds(1)

        postJson(
            entriesPath(),
            """{"projectId":"$projectId","title":"Long shift","startedAt":"$nineAm","endedAt":"$justUnder"}""",
            bob.accessToken,
        ).andExpect(status().isCreated)

        postJson(
            entriesPath(),
            """{"projectId":"$projectId","title":"Too long","startedAt":"$nineAm","endedAt":"$justOver"}""",
            bob.accessToken,
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.detail").value("A time entry cannot be longer than 24 hours"))

        assertThat(timeEntryRepository.count()).isEqualTo(1)
    }

    @Test
    fun `exactly a day is accepted`() {
        postJson(
            entriesPath(),
            """{"projectId":"$projectId","title":"A full day",""" +
                """"startedAt":"$nineAm","endedAt":"${nineAm.plus(TimeEntry.MAX_DURATION)}"}""",
            bob.accessToken,
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.durationSeconds").value(TimeEntry.MAX_DURATION.seconds))
    }

    @Test
    fun `an edit cannot stretch an entry past the ceiling`() {
        val id = createTimeEntry(organizationId, projectId, bob, nineAm, elevenAm)

        patchJson(
            "${entriesPath()}/$id",
            """{"endedAt":"${nineAm.plus(TimeEntry.MAX_DURATION).plusSeconds(1)}"}""",
            bob.accessToken,
        ).andExpect(status().isUnprocessableEntity)

        assertThat(timeEntryRepository.findById(id).orElseThrow().durationSeconds).isEqualTo(7200)
    }

    @Test
    fun `a forgotten timer records the ceiling rather than becoming impossible to stop`() {
        val timerId = startTimer(organizationId, projectId, bob)

        // Backdate the start well past the cap, as a timer left running over a weekend would be.
        val longAgo = Instant.now().minus(Duration.ofDays(3))
        timeEntryRepository.findById(timerId).orElseThrow()
            .also { it.startedAt = longAgo }
            .let { timeEntryRepository.saveAndFlush(it) }

        // Still visible, and its displayed elapsed time is capped rather than showing three days.
        getRequest(timerPath(), bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.durationSeconds").value(TimeEntry.MAX_DURATION.seconds))

        postJson("${entriesPath()}/$timerId/stop", "", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.durationSeconds").value(TimeEntry.MAX_DURATION.seconds))

        val entry = timeEntryRepository.findById(timerId).orElseThrow()
        assertThat(entry.durationSeconds).isEqualTo(TimeEntry.MAX_DURATION.seconds)

        // Stated against the stored start rather than the local one: PostgreSQL keeps
        // timestamps to microseconds, so comparing with an in-memory Instant would be
        // asserting the precision of `Instant.now()` rather than the behaviour under test.
        assertThat(entry.endedAt).isEqualTo(entry.startedAt.plus(TimeEntry.MAX_DURATION))

        // ...and the slot is free again, which refusing the stop would not have achieved.
        startTimer(organizationId, projectId, bob)
    }

    @Test
    fun `an ordinary timer is unaffected by the ceiling`() {
        val timerId = startTimer(organizationId, projectId, bob)

        postJson("${entriesPath()}/$timerId/stop", "", bob.accessToken).andExpect(status().isOk)

        assertThat(timeEntryRepository.findById(timerId).orElseThrow().durationSeconds).isLessThan(60)
    }
}
