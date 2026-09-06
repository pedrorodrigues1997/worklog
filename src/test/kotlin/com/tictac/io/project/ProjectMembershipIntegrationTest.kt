package com.tictac.io.project

import com.tictac.io.organization.OrganizationRole
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@DisplayName("Project membership: assignment and removal")
class ProjectMembershipIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var bob: TestUser
    private lateinit var sarah: TestUser
    private var organizationId = UUID.randomUUID()
    private var projectId = UUID.randomUUID()

    @BeforeEach
    fun createOrganizationAndProject() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        bob = newUser("bob@example.com")
        sarah = newUser("sarah@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, bob, OrganizationRole.MEMBER)
        addMember(organizationId, sarah, OrganizationRole.MEMBER)

        projectId = createProject(organizationId, owner, "Website Redesign")
    }

    private fun membersPath() = "/api/organizations/$organizationId/projects/$projectId/members"

    private fun assign(target: TestUser, caller: TestUser) =
        postJson(membersPath(), """{"userId":"${target.id}"}""", caller.accessToken)

    // --- assignment -------------------------------------------------------------------

    @Test
    fun `an owner can assign an organization member to a project`() {
        assign(bob, caller = owner)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.userId").value(bob.id.toString()))
            .andExpect(jsonPath("$.email").value("bob@example.com"))
            .andExpect(jsonPath("$.firstName").value("John"))
            .andExpect(jsonPath("$.assignedAt").isNotEmpty)

        assertThat(isAssigned(projectId, bob)).isTrue()
    }

    @Test
    fun `an admin can assign a member`() {
        assign(sarah, caller = admin).andExpect(status().isCreated)

        assertThat(isAssigned(projectId, sarah)).isTrue()
    }

    @Test
    fun `a member cannot assign anyone, including themselves`() {
        assign(sarah, caller = bob).andExpect(status().isNotFound)
        assign(bob, caller = bob).andExpect(status().isNotFound)

        // Bob cannot even see this project, so 404. Once assigned, he can - and then the
        // refusal becomes 403, which leaks nothing he could not already see.
        assignToProject(organizationId, projectId, bob, owner)
        assign(sarah, caller = bob).andExpect(status().isForbidden)

        assertThat(isAssigned(projectId, sarah)).isFalse()
    }

    @Test
    fun `organization membership alone never grants project membership`() {
        // Everyone here belongs to Acme; nobody is on the project until told.
        assertThat(projectMemberRepository.countByProjectId(projectId)).isZero()

        listOf(owner, admin, bob, sarah).forEach { assertThat(isAssigned(projectId, it)).isFalse() }
    }

    @Test
    fun `a user from another organization cannot be assigned`() {
        val stranger = newUser("stranger@example.com")
        createOrganization(stranger, "Other Company")

        assign(stranger, caller = owner).andExpect(status().isNotFound)

        assertThat(isAssigned(projectId, stranger)).isFalse()
    }

    @Test
    fun `a user who belongs to no organization at all cannot be assigned`() {
        val nobody = newUser("nobody@example.com")

        assign(nobody, caller = owner).andExpect(status().isNotFound)

        assertThat(isAssigned(projectId, nobody)).isFalse()
    }

    @Test
    fun `a user that does not exist cannot be assigned`() {
        postJson(membersPath(), """{"userId":"${UUID.randomUUID()}"}""", owner.accessToken)
            .andExpect(status().isNotFound)

        assertThat(projectMemberRepository.countByProjectId(projectId)).isZero()
    }

    @Test
    fun `a closed account cannot be assigned`() {
        // Bob closes his own account - allowed, he owns nothing - which leaves his
        // organization membership row in place. That row must not be a route onto a project.
        deleteRequest("/api/users/me", bob.accessToken).andExpect(status().isNoContent)
        assertThat(roleOf(organizationId, bob)).isEqualTo(OrganizationRole.MEMBER)

        assign(bob, caller = owner)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Cannot assign this user"))

        assertThat(isAssigned(projectId, bob)).isFalse()
    }

    @Test
    fun `a duplicate assignment is rejected`() {
        assign(bob, caller = owner).andExpect(status().isCreated)

        assign(bob, caller = owner)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Cannot assign this user"))
        assign(bob, caller = admin).andExpect(status().isConflict)

        assertThat(projectMemberRepository.countByProjectId(projectId)).isEqualTo(1)
    }

    @Test
    fun `the database refuses a duplicate assignment even with the service out of the way`() {
        assign(bob, caller = owner).andExpect(status().isCreated)

        // The application checks first so the common case is a clean 409, but the unique
        // index is the actual guarantee - two concurrent assignments both pass that check.
        assertThatThrownBy {
            projectMemberRepository.saveAndFlush(ProjectMember(projectId = projectId, userId = bob.id))
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(projectMemberRepository.countByProjectId(projectId)).isEqualTo(1)
    }

    @Test
    fun `the same user can be assigned to several projects`() {
        val second = createProject(organizationId, owner, "Mobile App")

        assign(bob, caller = owner).andExpect(status().isCreated)
        postJson(
            "/api/organizations/$organizationId/projects/$second/members",
            """{"userId":"${bob.id}"}""",
            owner.accessToken,
        ).andExpect(status().isCreated)

        assertThat(isAssigned(projectId, bob)).isTrue()
        assertThat(isAssigned(second, bob)).isTrue()
    }

    @Test
    fun `the user id is required and must be a uuid`() {
        listOf("""{}""", """{"userId":null}""", """{"userId":""}""", """{"userId":"not-a-uuid"}""")
            .forEach { postJson(membersPath(), it, owner.accessToken).andExpect(status().isBadRequest) }

        assertThat(projectMemberRepository.countByProjectId(projectId)).isZero()
    }

    // --- listing ------------------------------------------------------------------------

    @Test
    fun `administrators and assigned members can see the project's members`() {
        assign(bob, caller = owner).andExpect(status().isCreated)
        assign(sarah, caller = owner).andExpect(status().isCreated)

        listOf(owner, admin, bob, sarah).forEach { user ->
            getRequest(membersPath(), user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(2)))
        }
    }

    @Test
    fun `an unassigned member cannot see the project's members`() {
        assign(bob, caller = owner).andExpect(status().isCreated)

        val body = getRequest(membersPath(), sarah.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("bob@example.com")
    }

    @Test
    fun `the member list returns safe user fields and no authentication secrets`() {
        assign(bob, caller = owner).andExpect(status().isCreated)

        val body = getRequest(membersPath(), owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].userId").value(bob.id.toString()))
            .andExpect(jsonPath("$[0].firstName").value("John"))
            .andExpect(jsonPath("$[0].lastName").value("Smith"))
            .andExpect(jsonPath("$[0].email").value("bob@example.com"))
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("argon2")
        assertThat(body).doesNotContain("passwordHash")
        assertThat(body).doesNotContain("password_hash")
        assertThat(body).doesNotContain("password")
    }

    @Test
    fun `a closed account keeps its assignment row but drops out of the listing`() {
        assign(bob, caller = owner).andExpect(status().isCreated)
        assign(sarah, caller = owner).andExpect(status().isCreated)

        deleteRequest("/api/users/me", bob.accessToken).andExpect(status().isNoContent)

        // Same treatment as the organization member listing: a soft-deleted user cannot
        // authenticate, so publishing their address would be wrong. Closure does not remove
        // memberships - see AccountClosureService - so the row itself survives.
        val body = getRequest(membersPath(), owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("bob@example.com")
        assertThat(isAssigned(projectId, bob)).isTrue()
    }

    // --- removal --------------------------------------------------------------------------

    @Test
    fun `an owner and an admin can remove a project member`() {
        assign(bob, caller = owner).andExpect(status().isCreated)
        assign(sarah, caller = owner).andExpect(status().isCreated)

        deleteRequest("${membersPath()}/${bob.id}", owner.accessToken).andExpect(status().isNoContent)
        deleteRequest("${membersPath()}/${sarah.id}", admin.accessToken).andExpect(status().isNoContent)

        assertThat(projectMemberRepository.countByProjectId(projectId)).isZero()
    }

    @Test
    fun `a removed member immediately loses sight of the project`() {
        assign(bob, caller = owner).andExpect(status().isCreated)
        getRequest("/api/organizations/$organizationId/projects/$projectId", bob.accessToken)
            .andExpect(status().isOk)

        deleteRequest("${membersPath()}/${bob.id}", owner.accessToken).andExpect(status().isNoContent)

        // Same still-valid access token; the assignment row was what granted the view.
        getRequest("/api/organizations/$organizationId/projects/$projectId", bob.accessToken)
            .andExpect(status().isNotFound)
        getRequest(membersPath(), bob.accessToken).andExpect(status().isNotFound)
        getRequest("/api/organizations/$organizationId/projects", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(0)))
    }

    @Test
    fun `removal leaves the organization membership and the user account alone`() {
        assign(bob, caller = owner).andExpect(status().isCreated)

        deleteRequest("${membersPath()}/${bob.id}", owner.accessToken).andExpect(status().isNoContent)

        assertThat(roleOf(organizationId, bob)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(userRepository.findById(bob.id)).isPresent
        getRequest("/api/organizations/$organizationId", bob.accessToken).andExpect(status().isOk)
    }

    @Test
    fun `a member cannot remove another project member`() {
        assign(bob, caller = owner).andExpect(status().isCreated)
        assign(sarah, caller = owner).andExpect(status().isCreated)

        deleteRequest("${membersPath()}/${sarah.id}", bob.accessToken).andExpect(status().isForbidden)

        assertThat(isAssigned(projectId, sarah)).isTrue()
    }

    @Test
    fun `a member cannot remove themselves - leaving is not an operation anywhere yet`() {
        assign(bob, caller = owner).andExpect(status().isCreated)

        // Deliberate: nothing in this codebase lets anyone leave anything on their own, and
        // making project assignment the sole exception would be inconsistent for no product
        // need. Documented on ProjectMembershipService.removeMember.
        deleteRequest("${membersPath()}/${bob.id}", bob.accessToken).andExpect(status().isForbidden)

        assertThat(isAssigned(projectId, bob)).isTrue()
    }

    @Test
    fun `an administrator may remove their own assignment`() {
        assign(admin, caller = owner).andExpect(status().isCreated)

        // Not a special case: they are using the same power over themselves that they hold
        // over everyone else, and they keep full access either way.
        deleteRequest("${membersPath()}/${admin.id}", admin.accessToken).andExpect(status().isNoContent)

        assertThat(isAssigned(projectId, admin)).isFalse()
        getRequest("/api/organizations/$organizationId/projects/$projectId", admin.accessToken)
            .andExpect(status().isOk)
    }

    @Test
    fun `removing someone who is not assigned is a not found`() {
        deleteRequest("${membersPath()}/${bob.id}", owner.accessToken).andExpect(status().isNotFound)
        deleteRequest("${membersPath()}/${UUID.randomUUID()}", owner.accessToken).andExpect(status().isNotFound)
    }

    @Test
    fun `membership endpoints require authentication`() {
        getRequest(membersPath()).andExpect(status().isUnauthorized)
        postJson(membersPath(), """{"userId":"${bob.id}"}""").andExpect(status().isUnauthorized)
        deleteRequest("${membersPath()}/${bob.id}").andExpect(status().isUnauthorized)

        assertThat(projectMemberRepository.countByProjectId(projectId)).isZero()
    }
}
