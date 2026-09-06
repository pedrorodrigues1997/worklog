package com.tictac.io.organization

import com.jayway.jsonpath.JsonPath
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * An invitation belongs to exactly one organization, and nothing a client sends can change
 * which one.
 *
 * The structural reason is worth stating plainly: **neither acceptance endpoint takes an
 * organization id.** The target comes off the invitation row. So the usual attack shape -
 * pass a real token and a different organization id - has no parameter to occupy, and these
 * tests confirm that adding one to the body achieves nothing.
 */
@DisplayName("Tenant isolation: invitations")
class InvitationTenantIsolationIntegrationTest : OrganizationApiTest() {

    private lateinit var ownerA: TestUser
    private lateinit var ownerB: TestUser
    private lateinit var bob: TestUser
    private var organizationA = UUID.randomUUID()
    private var organizationB = UUID.randomUUID()

    @BeforeEach
    fun createTwoTenants() {
        ownerA = newUser("owner-a@example.com")
        ownerB = newUser("owner-b@example.com")
        bob = newUser("bob@example.com")

        organizationA = createOrganization(ownerA, "Organization A")
        organizationB = createOrganization(ownerB, "Organization B")

        // Both are one-member organizations, so each needs a seat for the person joining.
        subscribeOrganization(organizationA, licenses = 2)
        subscribeOrganization(organizationB, licenses = 2)
    }

    @Test
    fun `an invitation from organization A creates membership only in organization A`() {
        val token = inviteToOrganization(organizationA, "bob@example.com", ownerA)

        postJson("/api/invitations/accept", """{"token":"$token"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organizationId").value(organizationA.toString()))

        assertThat(roleOf(organizationA, bob)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(roleOf(organizationB, bob)).isNull()

        // ...and organization B stays as unreachable to Bob as it was before.
        getRequest("/api/organizations/$organizationB", bob.accessToken).andExpect(status().isNotFound)
    }

    @Test
    fun `an organization id in the request body is ignored`() {
        val token = inviteToOrganization(organizationA, "bob@example.com", ownerA)

        postJson(
            "/api/invitations/accept",
            """{"token":"$token","organizationId":"$organizationB","organization_id":"$organizationB"}""",
            bob.accessToken,
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organizationId").value(organizationA.toString()))

        assertThat(roleOf(organizationB, bob)).isNull()
        assertThat(organizationMemberRepository.findAll().none { it.organizationId == organizationB && it.userId == bob.id })
            .isTrue()
    }

    @Test
    fun `an organization id in the registration body is ignored too`() {
        val token = inviteToOrganization(organizationA, "newcomer@example.com", ownerA)

        postJson(
            "/api/invitations/register",
            """{"token":"$token","firstName":"New","lastName":"Comer","email":"newcomer@example.com",""" +
                """"password":"$DEFAULT_PASSWORD","organizationId":"$organizationB"}""",
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.organizationId").value(organizationA.toString()))

        val newcomer = userRepository.findByEmail("newcomer@example.com")!!
        assertThat(organizationMemberRepository.findByOrganizationIdAndUserId(organizationA, newcomer.id!!))
            .isNotNull
        assertThat(organizationMemberRepository.findByOrganizationIdAndUserId(organizationB, newcomer.id!!))
            .isNull()
    }

    @Test
    fun `organization B's owner cannot issue an invitation into organization A`() {
        postJson(
            "/api/organizations/$organizationA/invitations",
            """{"email":"bob@example.com"}""",
            ownerB.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(organizationInvitationRepository.count()).isZero()
    }

    @Test
    fun `organization B's owner cannot see or consume organization A's invitation`() {
        val token = inviteToOrganization(organizationA, "owner-b@example.com", ownerA)

        // Owner B *is* the invited address here, so this one legitimately works - and it
        // makes them a MEMBER of A while leaving them OWNER of B. The point is that the two
        // standings stay separate.
        postJson("/api/invitations/accept", """{"token":"$token"}""", ownerB.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("MEMBER"))

        assertThat(roleOf(organizationA, ownerB)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(roleOf(organizationB, ownerB)).isEqualTo(OrganizationRole.OWNER)

        // Being an OWNER somewhere grants nothing in A.
        postJson("/api/organizations/$organizationA/invitations", """{"email":"x@example.com"}""", ownerB.accessToken)
            .andExpect(status().isForbidden)
    }

    @Test
    fun `two invitations to the same address from different organizations stay independent`() {
        val tokenA = inviteToOrganization(organizationA, "bob@example.com", ownerA)
        val tokenB = inviteToOrganization(organizationB, "bob@example.com", ownerB)

        postJson("/api/invitations/accept", """{"token":"$tokenA"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organizationId").value(organizationA.toString()))

        assertThat(roleOf(organizationA, bob)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(roleOf(organizationB, bob)).isNull()

        // B's invitation is untouched by A's acceptance and still works.
        postJson("/api/invitations/accept", """{"token":"$tokenB"}""", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organizationId").value(organizationB.toString()))

        assertThat(roleOf(organizationB, bob)).isEqualTo(OrganizationRole.MEMBER)
        getRequest("/api/organizations", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(2)))
    }

    @Test
    fun `a token from one organization cannot be swapped onto another organization's invitation`() {
        val tokenA = inviteToOrganization(organizationA, "bob@example.com", ownerA)
        val invitationB = inviteToOrganization(organizationB, "bob@example.com", ownerB)
            .let { organizationInvitationRepository.findAll().single { i -> i.organizationId == organizationB } }

        // Presenting A's token resolves A's invitation, whatever else is in the request -
        // the lookup is by token hash and nothing else.
        val body = postJson(
            "/api/invitations/accept",
            """{"token":"$tokenA","invitationId":"${invitationB.id}"}""",
            bob.accessToken,
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(JsonPath.read<String>(body, "$.organizationId")).isEqualTo(organizationA.toString())
        assertThat(roleOf(organizationB, bob)).isNull()
    }
}
