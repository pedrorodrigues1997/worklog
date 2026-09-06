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

@DisplayName("Project API: creation, listing, reading and updating")
class ProjectApiIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var bob: TestUser
    private lateinit var sarah: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        bob = newUser("bob@example.com")
        sarah = newUser("sarah@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, bob, OrganizationRole.MEMBER)
        addMember(organizationId, sarah, OrganizationRole.MEMBER)
    }

    private fun projectsPath() = "/api/organizations/$organizationId/projects"

    // --- creation -------------------------------------------------------------------

    @Test
    fun `an owner can create a project, and it belongs to the organization in the url`() {
        val body = postJson(
            projectsPath(),
            """{"name":"Website Redesign","description":"Redesign the company website"}""",
            owner.accessToken,
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Website Redesign"))
            .andExpect(jsonPath("$.description").value("Redesign the company website"))
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.createdAt").isNotEmpty)
            .andExpect(jsonPath("$.updatedAt").isNotEmpty)
            .andReturn().response.contentAsString

        val projectId = UUID.fromString(JsonPath.read(body, "$.id"))
        val project = projectRepository.findById(projectId).orElseThrow()

        assertThat(project.organizationId).isEqualTo(organizationId)
        assertThat(project.isActive).isTrue()

        // Creating a project assigns nobody, not even its creator. Organization membership
        // and project assignment are separate questions.
        assertThat(projectMemberRepository.countByProjectId(projectId)).isZero()
    }

    @Test
    fun `an admin can create a project`() {
        val projectId = createProject(organizationId, admin, "Mobile App")

        assertThat(projectRepository.findById(projectId).orElseThrow().organizationId).isEqualTo(organizationId)
    }

    @Test
    fun `a member cannot create a project`() {
        postJson(projectsPath(), """{"name":"Sneaky Project"}""", bob.accessToken)
            .andExpect(status().isForbidden)

        assertThat(projectRepository.count()).isZero()
    }

    @Test
    fun `an unauthenticated user cannot create a project`() {
        postJson(projectsPath(), """{"name":"Website Redesign"}""")
            .andExpect(status().isUnauthorized)

        assertThat(projectRepository.count()).isZero()
    }

    @Test
    fun `a non-member cannot create a project, and is not told the organization exists`() {
        val outsider = newUser("outsider@example.com")

        postJson(projectsPath(), """{"name":"Website Redesign"}""", outsider.accessToken)
            .andExpect(status().isNotFound)

        assertThat(projectRepository.count()).isZero()
    }

    @Test
    fun `the project name is required and bounded`() {
        listOf("""{"name":""}""", """{"name":"   "}""", """{}""")
            .forEach { postJson(projectsPath(), it, owner.accessToken).andExpect(status().isBadRequest) }

        val tooLongName = "a".repeat(Project.MAX_NAME_LENGTH + 1)
        postJson(projectsPath(), """{"name":"$tooLongName"}""", owner.accessToken)
            .andExpect(status().isBadRequest)

        val tooLongDescription = "a".repeat(Project.MAX_DESCRIPTION_LENGTH + 1)
        postJson(projectsPath(), """{"name":"Fine","description":"$tooLongDescription"}""", owner.accessToken)
            .andExpect(status().isBadRequest)

        assertThat(projectRepository.count()).isZero()
    }

    @Test
    fun `the name is trimmed and a blank description is stored as nothing`() {
        val body = postJson(
            projectsPath(),
            """{"name":"  Website Redesign  ","description":"   "}""",
            owner.accessToken,
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Website Redesign"))
            .andExpect(jsonPath("$.description").doesNotExist())
            .andReturn().response.contentAsString

        val projectId = UUID.fromString(JsonPath.read(body, "$.id"))
        assertThat(projectRepository.findById(projectId).orElseThrow().description).isNull()
    }

    @Test
    fun `the organization cannot be redirected by a field in the body`() {
        val elsewhere = createOrganization(newUser("elsewhere@example.com"), "Other Company")

        val projectId = UUID.fromString(
            JsonPath.read(
                postJson(
                    projectsPath(),
                    """{"name":"Website Redesign","organizationId":"$elsewhere","organization_id":"$elsewhere"}""",
                    owner.accessToken,
                ).andExpect(status().isCreated).andReturn().response.contentAsString,
                "$.id",
            ),
        )

        assertThat(projectRepository.findById(projectId).orElseThrow().organizationId).isEqualTo(organizationId)
    }

    // --- listing ---------------------------------------------------------------------

    @Test
    fun `an owner and an admin see every project in the organization`() {
        createProject(organizationId, owner, "Website Redesign")
        createProject(organizationId, owner, "Mobile App")
        createProject(organizationId, owner, "Internal Tools")

        listOf(owner, admin).forEach { user ->
            getRequest(projectsPath(), user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(3)))
        }
    }

    @Test
    fun `a member sees only the projects they are assigned to`() {
        val website = createProject(organizationId, owner, "Website Redesign")
        val mobile = createProject(organizationId, owner, "Mobile App")
        createProject(organizationId, owner, "Internal Tools")

        assignToProject(organizationId, website, owner, owner)
        assignToProject(organizationId, website, admin, owner)
        assignToProject(organizationId, mobile, bob, owner)
        assignToProject(organizationId, mobile, sarah, owner)

        val body = getRequest(projectsPath(), bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andReturn().response.contentAsString

        assertThat(JsonPath.read<List<String>>(body, "$[*].name")).containsExactly("Mobile App")
        assertThat(body).doesNotContain("Website Redesign")
        assertThat(body).doesNotContain("Internal Tools")
    }

    @Test
    fun `a member with no assignments sees nothing, though the projects exist`() {
        createProject(organizationId, owner, "Website Redesign")
        createProject(organizationId, owner, "Mobile App")

        getRequest(projectsPath(), bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(0)))

        // An empty list because of a scope filter, not because the table is empty - which
        // is the failure mode this asserts against.
        assertThat(projectRepository.count()).isEqualTo(2)
    }

    @Test
    fun `the active filter selects what is and is not selectable for new work`() {
        val website = createProject(organizationId, owner, "Website Redesign")
        val mobile = createProject(organizationId, owner, "Mobile App")
        archive(website)

        // Unfiltered: everything, so an administrator can find the archive.
        getRequest(projectsPath(), owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))

        // active=true is the query time tracking will use.
        val activeBody = getRequest("${projectsPath()}?active=true", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andReturn().response.contentAsString
        assertThat(JsonPath.read<List<String>>(activeBody, "$[*].id")).containsExactly(mobile.toString())

        val archivedBody = getRequest("${projectsPath()}?active=false", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andReturn().response.contentAsString
        assertThat(JsonPath.read<List<String>>(archivedBody, "$[*].id")).containsExactly(website.toString())
    }

    @Test
    fun `the active filter also applies to a member's own scoped list`() {
        val website = createProject(organizationId, owner, "Website Redesign")
        val mobile = createProject(organizationId, owner, "Mobile App")
        assignToProject(organizationId, website, bob, owner)
        assignToProject(organizationId, mobile, bob, owner)
        archive(website)

        getRequest("${projectsPath()}?active=true", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].id").value(mobile.toString()))
    }

    @Test
    fun `listing requires authentication and organization membership`() {
        getRequest(projectsPath()).andExpect(status().isUnauthorized)
        getRequest(projectsPath(), newUser("outsider@example.com").accessToken).andExpect(status().isNotFound)
    }

    // --- reading one ------------------------------------------------------------------

    @Test
    fun `administrators can read any project, assigned or not`() {
        val projectId = createProject(organizationId, owner, "Website Redesign")

        listOf(owner, admin).forEach { user ->
            getRequest("${projectsPath()}/$projectId", user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("Website Redesign"))
        }

        // Neither of them is on the project - administering is not the same as being on it.
        assertThat(projectMemberRepository.countByProjectId(projectId)).isZero()
    }

    @Test
    fun `a member can read a project they are assigned to`() {
        val projectId = createProject(organizationId, owner, "Mobile App")
        assignToProject(organizationId, projectId, bob, owner)

        getRequest("${projectsPath()}/$projectId", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(projectId.toString()))
    }

    @Test
    fun `a member cannot read a project they are not assigned to, and cannot tell it exists`() {
        val projectId = createProject(organizationId, owner, "Website Redesign")

        val hidden = getRequest("${projectsPath()}/$projectId", bob.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        val imaginary = getRequest("${projectsPath()}/${UUID.randomUUID()}", bob.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        // Same answer for "exists but not yours" and "does not exist" - the refusal must
        // not confirm that a project by that id is real.
        listOf("$.status", "$.title", "$.detail").forEach { field ->
            assertThat(JsonPath.read<Any>(hidden, field)).isEqualTo(JsonPath.read<Any>(imaginary, field))
        }
        assertThat(hidden).doesNotContain("Website Redesign")
    }

    @Test
    fun `a project id that is not a uuid is a bad request`() {
        getRequest("${projectsPath()}/not-a-uuid", owner.accessToken).andExpect(status().isBadRequest)
    }

    // --- updating -----------------------------------------------------------------------

    @Test
    fun `an owner and an admin can update a project`() {
        val projectId = createProject(organizationId, owner, "Website Redesign", "Original")

        patchJson("${projectsPath()}/$projectId", """{"name":"Website Rebuild"}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Website Rebuild"))
            .andExpect(jsonPath("$.description").value("Original"))

        patchJson("${projectsPath()}/$projectId", """{"description":"Updated by admin"}""", admin.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Website Rebuild"))
            .andExpect(jsonPath("$.description").value("Updated by admin"))
    }

    @Test
    fun `an absent field is left alone and an empty description clears it`() {
        val projectId = createProject(organizationId, owner, "Website Redesign", "Original")

        patchJson("${projectsPath()}/$projectId", """{}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Website Redesign"))
            .andExpect(jsonPath("$.description").value("Original"))

        patchJson("${projectsPath()}/$projectId", """{"description":""}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.description").doesNotExist())

        assertThat(projectRepository.findById(projectId).orElseThrow().description).isNull()
    }

    @Test
    fun `updating stamps updated_at without touching created_at`() {
        val projectId = createProject(organizationId, owner, "Website Redesign")
        val before = projectRepository.findById(projectId).orElseThrow()
        val createdAt = before.createdAt
        val updatedAtBefore = before.updatedAt

        patchJson("${projectsPath()}/$projectId", """{"name":"Renamed"}""", owner.accessToken)
            .andExpect(status().isOk)

        val after = projectRepository.findById(projectId).orElseThrow()
        assertThat(after.createdAt).isEqualTo(createdAt)
        assertThat(after.updatedAt).isAfterOrEqualTo(updatedAtBefore)
    }

    @Test
    fun `a member cannot update a project, even one they are assigned to`() {
        val projectId = createProject(organizationId, owner, "Mobile App")
        assignToProject(organizationId, projectId, bob, owner)

        // Assigned, so the project is visible - 403 rather than 404, and it leaks nothing.
        patchJson("${projectsPath()}/$projectId", """{"name":"Bob's App"}""", bob.accessToken)
            .andExpect(status().isForbidden)

        // Not assigned, so it is not visible at all - 404, which must not confirm it exists.
        patchJson("${projectsPath()}/$projectId", """{"name":"Sarah's App"}""", sarah.accessToken)
            .andExpect(status().isNotFound)

        assertThat(projectRepository.findById(projectId).orElseThrow().name).isEqualTo("Mobile App")
    }

    @Test
    fun `update validates the new values`() {
        val projectId = createProject(organizationId, owner, "Website Redesign")

        listOf(
            """{"name":"${"a".repeat(Project.MAX_NAME_LENGTH + 1)}"}""",
            """{"description":"${"a".repeat(Project.MAX_DESCRIPTION_LENGTH + 1)}"}""",
        ).forEach { body ->
            patchJson("${projectsPath()}/$projectId", body, owner.accessToken).andExpect(status().isBadRequest)
        }

        assertThat(projectRepository.findById(projectId).orElseThrow().name).isEqualTo("Website Redesign")
    }

    // --- archiving ------------------------------------------------------------------------

    @Test
    fun `archiving is an update, and it is reversible`() {
        val projectId = createProject(organizationId, owner, "Website Redesign")

        patchJson("${projectsPath()}/$projectId", """{"isActive":false}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isActive").value(false))
        assertThat(projectRepository.findById(projectId).orElseThrow().isActive).isFalse()

        patchJson("${projectsPath()}/$projectId", """{"isActive":true}""", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isActive").value(true))
        assertThat(projectRepository.findById(projectId).orElseThrow().isActive).isTrue()
    }

    @Test
    fun `archiving twice is idempotent and does not duplicate the project`() {
        val projectId = createProject(organizationId, owner, "Website Redesign")

        repeat(3) {
            patchJson("${projectsPath()}/$projectId", """{"isActive":false}""", owner.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.isActive").value(false))
        }

        assertThat(projectRepository.count()).isEqualTo(1)
    }

    @Test
    fun `an archived project stays archived when a new one reuses its name`() {
        val archived = createProject(organizationId, owner, "Website Redesign")
        archive(archived)

        // Names are deliberately not unique within an organization: reviving a project name
        // for a new piece of work is normal, and it must create a distinct project rather
        // than resurrect or collide with the old one.
        val fresh = createProject(organizationId, owner, "Website Redesign")

        assertThat(fresh).isNotEqualTo(archived)
        assertThat(projectRepository.findById(archived).orElseThrow().isActive).isFalse()
        assertThat(projectRepository.findById(fresh).orElseThrow().isActive).isTrue()
        assertThat(projectRepository.count()).isEqualTo(2)
    }

    @Test
    fun `an archived project is still readable and still holds its members`() {
        val projectId = createProject(organizationId, owner, "Website Redesign")
        assignToProject(organizationId, projectId, bob, owner)
        archive(projectId)

        // Archiving is not deletion. Historical time entries will need the project and its
        // assignments to keep resolving.
        getRequest("${projectsPath()}/$projectId", owner.accessToken).andExpect(status().isOk)
        getRequest("${projectsPath()}/$projectId", bob.accessToken).andExpect(status().isOk)
        assertThat(isAssigned(projectId, bob)).isTrue()
    }

    private fun archive(projectId: UUID) {
        patchJson("${projectsPath()}/$projectId", """{"isActive":false}""", owner.accessToken)
            .andExpect(status().isOk)
    }
}
