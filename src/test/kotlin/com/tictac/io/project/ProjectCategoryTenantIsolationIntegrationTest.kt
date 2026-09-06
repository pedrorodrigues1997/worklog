package com.tictac.io.project

import com.jayway.jsonpath.JsonPath
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Two isolation boundaries, not one.
 *
 * The obvious one is between organizations: user A must not reach organization B's category
 * however they address it. The subtler one is *inside* an organization, between projects - a
 * category belongs to exactly one project, and an administrator who legitimately sees both
 * projects must still not be able to attach one project's category to another's time entry.
 * The second boundary is the one an implementation resolving categories by id alone would
 * fail, so it is tested from both directions.
 */
@DisplayName("Tenant isolation: project categories")
class ProjectCategoryTenantIsolationIntegrationTest : OrganizationApiTest() {

    private lateinit var userA: TestUser
    private lateinit var userB: TestUser
    private var organizationA = UUID.randomUUID()
    private var organizationB = UUID.randomUUID()
    private var projectA = UUID.randomUUID()
    private var projectB = UUID.randomUUID()
    private var categoryA = UUID.randomUUID()
    private var categoryB = UUID.randomUUID()

    // A second project inside organization A, for the cross-project half.
    private var siblingProject = UUID.randomUUID()
    private var siblingCategory = UUID.randomUUID()

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
        siblingProject = createProject(organizationA, userA, "Sibling Project")

        categoryA = createCategory(organizationA, projectA, userA, "Category A")
        categoryB = createCategory(organizationB, projectB, userB, "Category B")
        siblingCategory = createCategory(organizationA, siblingProject, userA, "Sibling Category")
    }

    /** Addressed through organization B, which user A does not belong to. */
    private fun viaB() = "/api/organizations/$organizationB/projects/$projectB/categories/$categoryB"

    /** Addressed through user A's own organization and project, carrying B's category id. */
    private fun viaOwn() = "/api/organizations/$organizationA/projects/$projectA/categories/$categoryB"

    // --- across organizations -----------------------------------------------------------

    @Test
    fun `user A cannot read category B`() {
        listOf(viaB(), viaOwn()).forEach { path ->
            val body = getRequest(path, userA.accessToken)
                .andExpect(status().isNotFound)
                .andReturn().response.contentAsString

            assertThat(body).doesNotContain("Category B")
        }
    }

    @Test
    fun `user A cannot list organization B's categories`() {
        val body = getRequest("/api/organizations/$organizationB/projects/$projectB/categories", userA.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("Category B")
    }

    @Test
    fun `user A cannot modify or deactivate category B`() {
        listOf(viaB(), viaOwn()).forEach { path ->
            patchJson(path, """{"name":"Taken Over","isActive":false}""", userA.accessToken)
                .andExpect(status().isNotFound)
        }

        val category = projectCategoryRepository.findById(categoryB).orElseThrow()
        assertThat(category.name).isEqualTo("Category B")
        assertThat(category.isActive).isTrue()
    }

    @Test
    fun `user A cannot create a category in organization B`() {
        postJson(
            "/api/organizations/$organizationB/projects/$projectB/categories",
            """{"name":"Planted"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(projectCategoryRepository.countByProjectId(projectB)).isEqualTo(1)
    }

    @Test
    fun `user A cannot create a time entry or timer using category B`() {
        postJson(
            "/api/organizations/$organizationA/time-entries",
            """{"projectId":"$projectA","projectCategoryId":"$categoryB","title":"Work",""" +
                """"startedAt":"$nineAm","endedAt":"$elevenAm"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        postJson(
            "/api/organizations/$organizationA/time-entries/timer",
            """{"projectId":"$projectA","projectCategoryId":"$categoryB","title":"Work"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `a foreign category id is indistinguishable from one that does not exist`() {
        val foreign = getRequest(viaOwn(), userA.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        val imaginary = getRequest(
            "/api/organizations/$organizationA/projects/$projectA/categories/${UUID.randomUUID()}",
            userA.accessToken,
        )
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        listOf("$.status", "$.title", "$.detail").forEach { field ->
            assertThat(JsonPath.read<Any>(foreign, field)).isEqualTo(JsonPath.read<Any>(imaginary, field))
        }
    }

    @Test
    fun `organization B's own people are unaffected`() {
        getRequest(viaB(), userB.accessToken).andExpect(status().isOk)
        patchJson(viaB(), """{"description":"Fine"}""", userB.accessToken).andExpect(status().isOk)
    }

    // --- across projects inside one organization -------------------------------------------

    @Test
    fun `a sibling project's category cannot be read through the wrong project`() {
        // User A administers both projects, so this is not about permission - it is about
        // the category belonging to exactly one project.
        getRequest(
            "/api/organizations/$organizationA/projects/$projectA/categories/$siblingCategory",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        getRequest(
            "/api/organizations/$organizationA/projects/$siblingProject/categories/$siblingCategory",
            userA.accessToken,
        ).andExpect(status().isOk)
    }

    @Test
    fun `a sibling project's category cannot be modified through the wrong project`() {
        patchJson(
            "/api/organizations/$organizationA/projects/$projectA/categories/$siblingCategory",
            """{"name":"Moved"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(projectCategoryRepository.findById(siblingCategory).orElseThrow().name)
            .isEqualTo("Sibling Category")
    }

    @Test
    fun `a sibling project's category cannot be attached to this project's time entry`() {
        postJson(
            "/api/organizations/$organizationA/time-entries",
            """{"projectId":"$projectA","projectCategoryId":"$siblingCategory","title":"Work",""" +
                """"startedAt":"$nineAm","endedAt":"$elevenAm"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        postJson(
            "/api/organizations/$organizationA/time-entries/timer",
            """{"projectId":"$projectA","projectCategoryId":"$siblingCategory","title":"Work"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(timeEntryRepository.count()).isZero()
    }

    @Test
    fun `an existing entry cannot be edited onto a sibling project's category`() {
        val id = createTimeEntry(organizationA, projectA, userA, nineAm, elevenAm, categoryId = categoryA)

        patchJson(
            "/api/organizations/$organizationA/time-entries/$id",
            """{"projectCategoryId":"$siblingCategory"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(timeEntryRepository.findById(id).orElseThrow().projectCategoryId).isEqualTo(categoryA)
    }

    @Test
    fun `each project's listing contains only its own categories`() {
        val ownBody = getRequest(
            "/api/organizations/$organizationA/projects/$projectA/categories",
            userA.accessToken,
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(JsonPath.read<List<String>>(ownBody, "$[*].name")).containsExactly("Category A")
        assertThat(ownBody).doesNotContain("Sibling Category")
        assertThat(ownBody).doesNotContain("Category B")
    }
}
