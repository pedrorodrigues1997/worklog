package com.tictac.io.project

import com.tictac.io.organization.OrganizationRole
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Removing someone from an organization removes them from its projects.
 *
 * The domain rule is that a project member is always an organization member. There is no
 * foreign key that can hold it up - `project_members` reaches the organization only through
 * `projects.organization_id`, one hop away - so the guarantee is that
 * `OrganizationMembershipService.removeMember` deletes the assignments in the same
 * transaction, and these tests are what hold it to that.
 */
@DisplayName("Organization member removal cascades to project assignments")
class OrganizationMemberRemovalCascadeIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var alice: TestUser
    private lateinit var bob: TestUser
    private var organizationId = UUID.randomUUID()
    private var website = UUID.randomUUID()
    private var mobile = UUID.randomUUID()

    @BeforeEach
    fun createOrganizationWithProjects() {
        owner = newUser("owner@example.com")
        alice = newUser("alice@example.com")
        bob = newUser("bob@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, alice, OrganizationRole.ADMIN)
        addMember(organizationId, bob, OrganizationRole.MEMBER)

        website = createProject(organizationId, owner, "Website Redesign")
        mobile = createProject(organizationId, owner, "Mobile App")
    }

    private fun removeFromOrganization(target: TestUser, caller: TestUser = owner) =
        deleteRequest("/api/organizations/$organizationId/members/${target.id}", caller.accessToken)

    @Test
    fun `removing Alice from Acme removes her from every Acme project`() {
        assignToProject(organizationId, website, alice, owner)
        assignToProject(organizationId, mobile, alice, owner)
        assignToProject(organizationId, website, bob, owner)

        removeFromOrganization(alice).andExpect(status().isNoContent)

        assertThat(roleOf(organizationId, alice)).isNull()
        assertThat(isAssigned(website, alice)).isFalse()
        assertThat(isAssigned(mobile, alice)).isFalse()

        // Nobody else is touched, and neither are the projects.
        assertThat(isAssigned(website, bob)).isTrue()
        assertThat(projectRepository.findAllByOrganizationIdOrderByCreatedAtAsc(organizationId)).hasSize(2)
    }

    @Test
    fun `no orphaned assignment survives anywhere in the table`() {
        assignToProject(organizationId, website, alice, owner)
        assignToProject(organizationId, mobile, alice, owner)

        removeFromOrganization(alice).andExpect(status().isNoContent)

        // Stated as the invariant rather than as a per-project check: every assignment row
        // in the database must belong to someone who is still a member of that project's
        // organization.
        projectMemberRepository.findAll().forEach { assignment ->
            val project = projectRepository.findById(assignment.projectId).orElseThrow()
            assertThat(
                organizationMemberRepository.findByOrganizationIdAndUserId(
                    project.organizationId,
                    assignment.userId,
                ),
            ).describedAs("orphaned assignment %s", assignment).isNotNull
        }
    }

    @Test
    fun `assignments in a different organization are left alone`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")
        addMember(otherOrganization, alice, OrganizationRole.MEMBER)
        val otherProject = createProject(otherOrganization, otherOwner, "Other Project")

        assignToProject(organizationId, website, alice, owner)
        assignToProject(otherOrganization, otherProject, alice, otherOwner)

        removeFromOrganization(alice).andExpect(status().isNoContent)

        // She left Acme, not the other company. A blanket "delete this user's assignments"
        // would have taken work she is still doing elsewhere.
        assertThat(isAssigned(website, alice)).isFalse()
        assertThat(isAssigned(otherProject, alice)).isTrue()
        assertThat(roleOf(otherOrganization, alice)).isEqualTo(OrganizationRole.MEMBER)
    }

    @Test
    fun `a removal that is refused changes nothing, assignments included`() {
        assignToProject(organizationId, website, alice, owner)
        assignToProject(organizationId, mobile, bob, owner)

        // A MEMBER may not remove anyone, and an ADMIN may not remove a peer ADMIN.
        removeFromOrganization(alice, caller = bob).andExpect(status().isForbidden)
        removeFromOrganization(owner, caller = alice).andExpect(status().isForbidden)

        assertThat(roleOf(organizationId, alice)).isEqualTo(OrganizationRole.ADMIN)
        assertThat(isAssigned(website, alice)).isTrue()
        assertThat(isAssigned(mobile, bob)).isTrue()
    }

    @Test
    fun `removing someone with no assignments is unremarkable`() {
        assignToProject(organizationId, website, alice, owner)

        removeFromOrganization(bob).andExpect(status().isNoContent)

        assertThat(roleOf(organizationId, bob)).isNull()
        assertThat(isAssigned(website, alice)).isTrue()
    }

    @Test
    fun `the removed member loses the projects from their listing immediately`() {
        assignToProject(organizationId, mobile, bob, owner)
        getRequest("/api/organizations/$organizationId/projects", bob.accessToken)
            .andExpect(status().isOk)

        removeFromOrganization(bob).andExpect(status().isNoContent)

        // Not merely unassigned - no longer a tenant of the organization at all.
        getRequest("/api/organizations/$organizationId/projects", bob.accessToken)
            .andExpect(status().isNotFound)
        getRequest("/api/organizations/$organizationId/projects/$mobile", bob.accessToken)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a re-added member does not get their old assignments back`() {
        assignToProject(organizationId, website, alice, owner)
        removeFromOrganization(alice).andExpect(status().isNoContent)

        // Re-joining is a fresh start: the assignment rows were deleted, not hidden.
        addMember(organizationId, alice, OrganizationRole.MEMBER)

        assertThat(isAssigned(website, alice)).isFalse()
        getRequest("/api/organizations/$organizationId/projects/$website", alice.accessToken)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `closing an account does not remove assignments, unlike being removed`() {
        assignToProject(organizationId, website, bob, owner)

        deleteRequest("/api/users/me", bob.accessToken).andExpect(status().isNoContent)

        // The two are different events and get different treatment on purpose. Removal
        // breaks the "project member is an organization member" invariant and so must
        // cascade; closure does not - Bob is still an Acme member, his account is simply
        // closed - so the rows stay, inert and filtered out of every listing.
        assertThat(roleOf(organizationId, bob)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(isAssigned(website, bob)).isTrue()
        assertThat(projectMemberRepository.findMembersOfProject(website)).isEmpty()
    }
}
