package com.tictac.io.organization

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

@DisplayName("Organization membership: constraints and removal")
class OrganizationMembershipIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var secondAdmin: TestUser
    private lateinit var member: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganizationWithEveryRole() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        secondAdmin = newUser("second-admin@example.com")
        member = newUser("member@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, secondAdmin, OrganizationRole.ADMIN)
        addMember(organizationId, member, OrganizationRole.MEMBER)
    }

    // --- database-level invariants -------------------------------------------------
    //
    // These bypass the API deliberately. The point is that the guarantee does not depend
    // on the service layer being correct: whatever writes a membership row - today's
    // create, tomorrow's invitation acceptance, or a manual fix-up - cannot break them.

    @Test
    fun `a user cannot be a member of the same organization twice`() {
        assertThatThrownBy {
            organizationMemberRepository.saveAndFlush(
                OrganizationMember(organizationId, member.id, OrganizationRole.ADMIN),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `an organization cannot have a second owner`() {
        // Someone with no membership yet, so the only constraint in play is the
        // one-OWNER-per-organization index rather than the (organization, user) one.
        val newcomer = newUser("newcomer@example.com")

        assertThatThrownBy {
            organizationMemberRepository.saveAndFlush(
                OrganizationMember(organizationId, newcomer.id, OrganizationRole.OWNER),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(roleOf(organizationId, newcomer)).isNull()

        // The same person may still own a different organization: the constraint is per
        // organization, not global.
        val other = createOrganization(newcomer, "Newcomer's Own Company")
        assertThat(roleOf(other, newcomer)).isEqualTo(OrganizationRole.OWNER)
        assertThat(organizationMemberRepository.countByOrganizationIdAndRole(other, OrganizationRole.OWNER))
            .isEqualTo(1)
    }

    @Test
    fun `the same user can hold different roles in different organizations`() {
        val other = createOrganization(member, "Member's Own Company")

        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(roleOf(other, member)).isEqualTo(OrganizationRole.OWNER)
    }

    // --- removal --------------------------------------------------------------------

    @Test
    fun `an owner can remove an admin`() {
        deleteRequest("/api/organizations/$organizationId/members/${admin.id}", owner.accessToken)
            .andExpect(status().isNoContent)

        assertThat(roleOf(organizationId, admin)).isNull()
    }

    @Test
    fun `an admin can remove a member`() {
        deleteRequest("/api/organizations/$organizationId/members/${member.id}", admin.accessToken)
            .andExpect(status().isNoContent)

        assertThat(roleOf(organizationId, member)).isNull()
    }

    @Test
    fun `a removed member immediately loses access to the organization`() {
        getRequest("/api/organizations/$organizationId", member.accessToken).andExpect(status().isOk)

        deleteRequest("/api/organizations/$organizationId/members/${member.id}", owner.accessToken)
            .andExpect(status().isNoContent)

        // Same still-valid access token; the membership row is what granted access.
        getRequest("/api/organizations/$organizationId", member.accessToken).andExpect(status().isNotFound)
        getRequest("/api/organizations/$organizationId/members", member.accessToken).andExpect(status().isNotFound)
        getRequest("/api/organizations", member.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(0)))
    }

    @Test
    fun `removing a member leaves their account and their other memberships alone`() {
        val elsewhere = createOrganization(member, "Member's Own Company")

        deleteRequest("/api/organizations/$organizationId/members/${member.id}", owner.accessToken)
            .andExpect(status().isNoContent)

        assertThat(userRepository.findById(member.id)).isPresent
        assertThat(roleOf(elsewhere, member)).isEqualTo(OrganizationRole.OWNER)
        getRequest("/api/organizations/$elsewhere", member.accessToken).andExpect(status().isOk)
    }

    @Test
    fun `a member cannot remove another member`() {
        val secondMember = newUser("second-member@example.com")
        addMember(organizationId, secondMember, OrganizationRole.MEMBER)

        deleteRequest("/api/organizations/$organizationId/members/${secondMember.id}", member.accessToken)
            .andExpect(status().isForbidden)

        assertThat(roleOf(organizationId, secondMember)).isEqualTo(OrganizationRole.MEMBER)
    }

    @Test
    fun `an admin cannot remove a fellow admin`() {
        deleteRequest("/api/organizations/$organizationId/members/${secondAdmin.id}", admin.accessToken)
            .andExpect(status().isForbidden)

        assertThat(roleOf(organizationId, secondAdmin)).isEqualTo(OrganizationRole.ADMIN)
    }

    @Test
    fun `the owner cannot be removed by an admin`() {
        deleteRequest("/api/organizations/$organizationId/members/${owner.id}", admin.accessToken)
            .andExpect(status().isForbidden)

        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
    }

    @Test
    fun `the owner cannot remove themselves and leave the organization ownerless`() {
        deleteRequest("/api/organizations/$organizationId/members/${owner.id}", owner.accessToken)
            .andExpect(status().isForbidden)

        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
        assertThat(organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER))
            .isEqualTo(1)
    }

    @Test
    fun `no sequence of role changes and removals can leave an organization without an owner`() {
        // Every operation an administrator has, aimed at the owner, from both sides.
        listOf(owner, admin).forEach { caller ->
            deleteRequest("/api/organizations/$organizationId/members/${owner.id}", caller.accessToken)
                .andExpect(status().isForbidden)
            patchJson(
                "/api/organizations/$organizationId/members/${owner.id}",
                """{"role":"MEMBER"}""",
                caller.accessToken,
            ).andExpect(status().isForbidden)
            patchJson(
                "/api/organizations/$organizationId/members/${owner.id}",
                """{"role":"ADMIN"}""",
                caller.accessToken,
            ).andExpect(status().isForbidden)
        }

        assertThat(organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER))
            .isEqualTo(1)
    }

    @Test
    fun `removing someone who is not a member is a not found`() {
        val stranger = newUser("stranger@example.com")

        deleteRequest("/api/organizations/$organizationId/members/${stranger.id}", owner.accessToken)
            .andExpect(status().isNotFound)
        deleteRequest("/api/organizations/$organizationId/members/${UUID.randomUUID()}", owner.accessToken)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `membership endpoints require authentication`() {
        getRequest("/api/organizations/$organizationId/members").andExpect(status().isUnauthorized)
        patchJson("/api/organizations/$organizationId/members/${member.id}", """{"role":"ADMIN"}""")
            .andExpect(status().isUnauthorized)
        deleteRequest("/api/organizations/$organizationId/members/${member.id}")
            .andExpect(status().isUnauthorized)

        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
    }
}
