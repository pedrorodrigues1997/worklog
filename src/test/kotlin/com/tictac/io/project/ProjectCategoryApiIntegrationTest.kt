package com.tictac.io.project

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
import java.util.UUID

@DisplayName("Project categories: creation, listing and configuration")
class ProjectCategoryApiIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var bob: TestUser
    private lateinit var sarah: TestUser
    private var organizationId = UUID.randomUUID()
    private var website = UUID.randomUUID()
    private var mobile = UUID.randomUUID()

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

        website = createProject(organizationId, owner, "Website Redesign")
        mobile = createProject(organizationId, owner, "Mobile App")
        assignToProject(organizationId, website, bob, owner)
    }

    private fun categoriesPath(projectId: UUID = website) =
        "/api/organizations/$organizationId/projects/$projectId/categories"

    private fun categoryPath(categoryId: UUID, projectId: UUID = website) =
        "${categoriesPath(projectId)}/$categoryId"

    // --- creation ----------------------------------------------------------------------

    @Test
    fun `an owner can create a category, and it belongs to the project in the url`() {
        val body = postJson(
            categoriesPath(),
            """{"name":"Development","description":"Software development work"}""",
            owner.accessToken,
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Development"))
            .andExpect(jsonPath("$.description").value("Software development work"))
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.projectId").value(website.toString()))
            .andExpect(jsonPath("$.createdByUserId").value(owner.id.toString()))
            .andReturn().response.contentAsString

        val category = projectCategoryRepository
            .findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow()

        assertThat(category.projectId).isEqualTo(website)
        assertThat(category.isActive).isTrue()
    }

    @Test
    fun `an admin can create a category`() {
        val id = createCategory(organizationId, website, admin, "Design")

        assertThat(projectCategoryRepository.findById(id).orElseThrow().createdByUserId).isEqualTo(admin.id)
    }

    @Test
    fun `a member cannot create a category, assigned to the project or not`() {
        // Bob is assigned, so he can see the project - 403, which leaks nothing.
        postJson(categoriesPath(), """{"name":"Development"}""", bob.accessToken)
            .andExpect(status().isForbidden)

        // Sarah is not assigned, so for her the project does not exist - 404.
        postJson(categoriesPath(), """{"name":"Development"}""", sarah.accessToken)
            .andExpect(status().isNotFound)

        assertThat(projectCategoryRepository.count()).isZero()
    }

    @Test
    fun `creating requires authentication`() {
        postJson(categoriesPath(), """{"name":"Development"}""").andExpect(status().isUnauthorized)

        assertThat(projectCategoryRepository.count()).isZero()
    }

    @Test
    fun `an archived project cannot receive a new category`() {
        archiveProject(organizationId, website, owner)

        postJson(categoriesPath(), """{"name":"Development"}""", owner.accessToken)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Project is archived"))

        assertThat(projectCategoryRepository.count()).isZero()
    }

    @Test
    fun `a duplicate name in the same project is rejected`() {
        createCategory(organizationId, website, owner, "Development")

        postJson(categoriesPath(), """{"name":"Development"}""", owner.accessToken)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Duplicate category name"))

        assertThat(projectCategoryRepository.countByProjectId(website)).isEqualTo(1)
    }

    @Test
    fun `a duplicate name differing only in case is rejected`() {
        createCategory(organizationId, website, owner, "Development")

        // Two entries a person cannot tell apart in a dropdown are the same category.
        listOf("development", "DEVELOPMENT", "DeVeLoPmEnT", "  development  ").forEach { name ->
            postJson(categoriesPath(), """{"name":"$name"}""", owner.accessToken)
                .andExpect(status().isConflict)
        }

        assertThat(projectCategoryRepository.countByProjectId(website)).isEqualTo(1)
    }

    @Test
    fun `an archived category still holds its name`() {
        val id = createCategory(organizationId, website, owner, "Meetings")
        archiveCategory(organizationId, website, id, owner)

        // Reactivating the old one is the way back, not creating a second nobody can tell
        // apart in a report.
        postJson(categoriesPath(), """{"name":"Meetings"}""", owner.accessToken)
            .andExpect(status().isConflict)
    }

    @Test
    fun `the same name is fine in a different project`() {
        val first = createCategory(organizationId, website, owner, "Development")
        val second = createCategory(organizationId, mobile, owner, "Development")

        // There is no global category list: these are two unrelated rows.
        assertThat(first).isNotEqualTo(second)
        assertThat(projectCategoryRepository.findById(first).orElseThrow().projectId).isEqualTo(website)
        assertThat(projectCategoryRepository.findById(second).orElseThrow().projectId).isEqualTo(mobile)
    }

    @Test
    fun `the database refuses a duplicate name even with the service out of the way`() {
        createCategory(organizationId, website, owner, "Development")

        // The application checks first for a clean 409, but the functional unique index on
        // (project_id, lower(name)) is the actual guarantee.
        org.assertj.core.api.Assertions.assertThatThrownBy {
            projectCategoryRepository.saveAndFlush(
                ProjectCategory(projectId = website, createdByUserId = owner.id, name = "DEVELOPMENT"),
            )
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)

        assertThat(projectCategoryRepository.countByProjectId(website)).isEqualTo(1)
    }

    @Test
    fun `an invalid name is rejected as a domain validation failure`() {
        listOf("""{}""", """{"name":null}""", """{"name":""}""", """{"name":"   "}""").forEach { body ->
            postJson(categoriesPath(), body, owner.accessToken)
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.title").value("Invalid project category"))
        }

        val tooLong = "a".repeat(ProjectCategory.MAX_NAME_LENGTH + 1)
        postJson(categoriesPath(), """{"name":"$tooLong"}""", owner.accessToken)
            .andExpect(status().isUnprocessableEntity)

        assertThat(projectCategoryRepository.count()).isZero()
    }

    @Test
    fun `the name is trimmed and a blank description is stored as nothing`() {
        val body = postJson(
            categoriesPath(),
            """{"name":"  Development  ","description":"   "}""",
            owner.accessToken,
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Development"))
            .andExpect(jsonPath("$.description").doesNotExist())
            .andReturn().response.contentAsString

        assertThat(
            projectCategoryRepository.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow().description,
        ).isNull()
    }

    @Test
    fun `a project with no categories is perfectly valid`() {
        // Nothing is created by default, and nothing needs to be.
        assertThat(projectCategoryRepository.countByProjectId(website)).isZero()

        getRequest(categoriesPath(), owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(0)))
    }

    // --- listing -------------------------------------------------------------------------

    @Test
    fun `administrators and assigned members see the project's categories`() {
        createCategory(organizationId, website, owner, "Development")
        createCategory(organizationId, website, owner, "Design")

        listOf(owner, admin, bob).forEach { user ->
            getRequest(categoriesPath(), user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(2)))
        }
    }

    @Test
    fun `an unassigned member cannot see a project's categories`() {
        createCategory(organizationId, website, owner, "Development")

        val body = getRequest(categoriesPath(), sarah.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("Development")
    }

    @Test
    fun `categories are ordered by name`() {
        createCategory(organizationId, website, owner, "Testing")
        createCategory(organizationId, website, owner, "Development")
        createCategory(organizationId, website, owner, "Meetings")

        val body = getRequest(categoriesPath(), owner.accessToken)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(JsonPath.read<List<String>>(body, "$[*].name"))
            .containsExactly("Development", "Meetings", "Testing")
    }

    @Test
    fun `archived categories are excluded by default`() {
        val development = createCategory(organizationId, website, owner, "Development")
        createCategory(organizationId, website, owner, "Meetings")
        archiveCategory(organizationId, website, development, owner)

        listOf(owner, admin, bob).forEach { user ->
            val body = getRequest(categoriesPath(), user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(1)))
                .andReturn().response.contentAsString

            assertThat(JsonPath.read<List<String>>(body, "$[*].name")).containsExactly("Meetings")
        }
    }

    @Test
    fun `administrators can ask for the archived ones`() {
        val development = createCategory(organizationId, website, owner, "Development")
        createCategory(organizationId, website, owner, "Meetings")
        archiveCategory(organizationId, website, development, owner)

        listOf(owner, admin).forEach { user ->
            getRequest("${categoriesPath()}?includeInactive=true", user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(2)))
        }
    }

    @Test
    fun `a member cannot use includeInactive to see more than they should`() {
        val development = createCategory(organizationId, website, owner, "Development")
        createCategory(organizationId, website, owner, "Meetings")
        archiveCategory(organizationId, website, development, owner)

        // The flag is ignored rather than refused: a member asking about configuration they
        // do not administer gets the active list they were always going to get.
        val body = getRequest("${categoriesPath()}?includeInactive=true", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andReturn().response.contentAsString

        assertThat(JsonPath.read<List<String>>(body, "$[*].name")).containsExactly("Meetings")

        // ...and it certainly does not widen access to a project they cannot see.
        getRequest("${categoriesPath()}?includeInactive=true", sarah.accessToken)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `another project's categories are never returned`() {
        createCategory(organizationId, website, owner, "Development")
        createCategory(organizationId, mobile, owner, "Research")

        val body = getRequest(categoriesPath(website), owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("Research")
    }

    // --- reading one -----------------------------------------------------------------------

    @Test
    fun `a category can be read by anyone who can see its project`() {
        val id = createCategory(organizationId, website, owner, "Development")

        listOf(owner, admin, bob).forEach { user ->
            getRequest(categoryPath(id), user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Development"))
        }

        getRequest(categoryPath(id), sarah.accessToken).andExpect(status().isNotFound)
    }

    @Test
    fun `a category cannot be reached through the wrong project`() {
        val id = createCategory(organizationId, mobile, owner, "Research")

        // Same organization, same caller, real category id - and the wrong project in the
        // path, which is what makes it unreachable.
        getRequest(categoryPath(id, website), owner.accessToken).andExpect(status().isNotFound)
        getRequest(categoryPath(id, mobile), owner.accessToken).andExpect(status().isOk)
    }

    @Test
    fun `a category that does not exist is a not found, and a bad id is a bad request`() {
        getRequest(categoryPath(UUID.randomUUID()), owner.accessToken).andExpect(status().isNotFound)
        getRequest("${categoriesPath()}/not-a-uuid", owner.accessToken).andExpect(status().isBadRequest)
    }

    // --- updating ---------------------------------------------------------------------------

    @Test
    fun `an owner and an admin can rename and re-describe a category`() {
        val id = createCategory(organizationId, website, owner, "Development", "Original")

        patchJson(categoryPath(id), """{"name":"Engineering"}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Engineering"))
            .andExpect(jsonPath("$.description").value("Original"))

        patchJson(categoryPath(id), """{"description":"Updated by admin"}""", admin.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Engineering"))
            .andExpect(jsonPath("$.description").value("Updated by admin"))
    }

    @Test
    fun `a member cannot update a category`() {
        val id = createCategory(organizationId, website, owner, "Development")

        patchJson(categoryPath(id), """{"name":"Bob's Category"}""", bob.accessToken)
            .andExpect(status().isForbidden)
        patchJson(categoryPath(id), """{"name":"Sarah's Category"}""", sarah.accessToken)
            .andExpect(status().isNotFound)

        assertThat(projectCategoryRepository.findById(id).orElseThrow().name).isEqualTo("Development")
    }

    @Test
    fun `deactivation and reactivation work`() {
        val id = createCategory(organizationId, website, owner, "Meetings")

        patchJson(categoryPath(id), """{"isActive":false}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isActive").value(false))
        assertThat(projectCategoryRepository.findById(id).orElseThrow().isActive).isFalse()

        patchJson(categoryPath(id), """{"isActive":true}""", admin.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isActive").value(true))
    }

    @Test
    fun `renaming onto another category's name is rejected`() {
        createCategory(organizationId, website, owner, "Development")
        val meetings = createCategory(organizationId, website, owner, "Meetings")

        listOf("Development", "development").forEach { name ->
            patchJson(categoryPath(meetings), """{"name":"$name"}""", owner.accessToken)
                .andExpect(status().isConflict)
        }

        assertThat(projectCategoryRepository.findById(meetings).orElseThrow().name).isEqualTo("Meetings")
    }

    @Test
    fun `a category can be recapitalised without colliding with itself`() {
        val id = createCategory(organizationId, website, owner, "development")

        patchJson(categoryPath(id), """{"name":"Development"}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Development"))
    }

    @Test
    fun `the project cannot be changed through an update`() {
        val id = createCategory(organizationId, website, owner, "Development")

        patchJson(
            categoryPath(id),
            """{"projectId":"$mobile","project_id":"$mobile","name":"Development"}""",
            owner.accessToken,
        ).andExpect(status().isOk)

        // Neither field exists on the request DTO. Moving a category would silently change
        // which project's work every entry naming it describes.
        assertThat(projectCategoryRepository.findById(id).orElseThrow().projectId).isEqualTo(website)
    }

    @Test
    fun `update validates the new name`() {
        val id = createCategory(organizationId, website, owner, "Development")

        listOf("""{"name":""}""", """{"name":"   "}""").forEach { body ->
            patchJson(categoryPath(id), body, owner.accessToken).andExpect(status().isUnprocessableEntity)
        }

        assertThat(projectCategoryRepository.findById(id).orElseThrow().name).isEqualTo("Development")
    }

    @Test
    fun `an absent field is left alone`() {
        val id = createCategory(organizationId, website, owner, "Development", "Original")

        patchJson(categoryPath(id), """{}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Development"))
            .andExpect(jsonPath("$.description").value("Original"))
            .andExpect(jsonPath("$.isActive").value(true))
    }

    @Test
    fun `there is no delete endpoint - retiring is the lifecycle`() {
        val id = createCategory(organizationId, website, owner, "Development")

        deleteRequest(categoryPath(id), owner.accessToken).andExpect(status().isMethodNotAllowed)

        assertThat(projectCategoryRepository.findById(id)).isPresent
    }
}
