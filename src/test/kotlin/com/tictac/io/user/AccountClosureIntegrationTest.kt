package com.tictac.io.user

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

@DisplayName("Account closure and its interaction with organizations")
class AccountClosureIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var member: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        member = newUser("member@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, member, OrganizationRole.MEMBER)
    }

    private fun closeAccount(user: TestUser) = deleteRequest("/api/users/me", user.accessToken)

    private fun deletedAtOf(user: TestUser) = userRepository.findById(user.id).orElseThrow().deletedAt

    // --- the blocking rule ------------------------------------------------------------

    @Test
    fun `an owner cannot close their account while they still own an organization`() {
        val body = closeAccount(owner)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Account still owns organizations"))
            .andExpect(jsonPath("$.organizations", hasSize<Any>(1)))
            .andExpect(jsonPath("$.organizations[0].id").value(organizationId.toString()))
            .andExpect(jsonPath("$.organizations[0].name").value("Acme"))
            .andReturn().response.contentAsString

        // The message has to tell the user what to do about it.
        assertThat(JsonPath.read<String>(body, "$.detail")).contains("Transfer ownership")

        assertThat(deletedAtOf(owner)).isNull()
    }

    @Test
    fun `a blocked closure changes nothing at all`() {
        closeAccount(owner).andExpect(status().isConflict)

        val organization = organizationRepository.findById(organizationId).orElseThrow()
        assertThat(organization.deletedAt).isNull()
        assertThat(organization.name).isEqualTo("Acme")

        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(organizationId, admin)).isEqualTo(OrganizationRole.ADMIN)
        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER))
            .isEqualTo(1)

        // The account is still fully usable.
        getRequest("/api/users/me", owner.accessToken).andExpect(status().isOk)
        getRequest("/api/organizations/$organizationId", owner.accessToken).andExpect(status().isOk)
    }

    @Test
    fun `every organization the caller owns is listed, not just the first`() {
        val second = createOrganization(owner, "Second Company")

        val body = closeAccount(owner)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.organizations", hasSize<Any>(2)))
            .andReturn().response.contentAsString

        assertThat(JsonPath.read<List<String>>(body, "$.organizations[*].id"))
            .containsExactlyInAnyOrder(organizationId.toString(), second.toString())
    }

    @Test
    fun `owning a soft-deleted organization does not block closure`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken).andExpect(status().isNoContent)

        // Requiring a transfer here would be a dead end: transfer refuses soft-deleted
        // organizations too, so the account could never be closed.
        closeAccount(owner).andExpect(status().isNoContent)

        assertThat(deletedAtOf(owner)).isNotNull()
    }

    // --- the intended lifecycle ---------------------------------------------------------

    @Test
    fun `transfer first, then close - the whole lifecycle`() {
        closeAccount(owner).andExpect(status().isConflict)

        postJson(
            "/api/organizations/$organizationId/transfer-ownership",
            """{"userId":"${admin.id}"}""",
            owner.accessToken,
        ).andExpect(status().isOk)

        closeAccount(owner).andExpect(status().isNoContent)

        assertThat(deletedAtOf(owner)).isNotNull()
        assertThat(roleOf(organizationId, admin)).isEqualTo(OrganizationRole.OWNER)
        assertThat(organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER))
            .isEqualTo(1)

        // The organization survives its founder leaving, with an owner who can still run it.
        assertThat(organizationRepository.findById(organizationId).orElseThrow().deletedAt).isNull()
        getRequest("/api/organizations/$organizationId", admin.accessToken).andExpect(status().isOk)
        assertActiveOrganizationsHaveAnActiveOwner()
    }

    @Test
    fun `an active organization is never left owned by a closed account`() {
        val second = createOrganization(admin, "Admin's Own Company")

        // Everyone tries to leave. Only the two who own nothing may.
        closeAccount(owner).andExpect(status().isConflict)
        closeAccount(admin).andExpect(status().isConflict)
        closeAccount(member).andExpect(status().isNoContent)

        assertActiveOrganizationsHaveAnActiveOwner()
        assertThat(organizationRepository.findById(second).orElseThrow().deletedAt).isNull()
    }

    /**
     * The invariant the whole feature exists for, stated directly against the database
     * rather than inferred from any one endpoint's behaviour.
     */
    private fun assertActiveOrganizationsHaveAnActiveOwner() {
        val activeOrganizations = organizationRepository.findAll().filter { it.deletedAt == null }
        assertThat(activeOrganizations).isNotEmpty

        activeOrganizations.forEach { organization ->
            // Read straight from the membership table, which - unlike the member listing -
            // does not filter closed accounts out, so a deleted owner would be visible here.
            val owners = organizationMemberRepository.findAll()
                .filter { it.organizationId == organization.id && it.role == OrganizationRole.OWNER }

            assertThat(owners).describedAs("owners of %s", organization.name).hasSize(1)
            assertThat(userRepository.findById(owners.single().userId).orElseThrow().deletedAt)
                .describedAs("owner of %s is a closed account", organization.name)
                .isNull()
        }
    }

    // --- closure for non-owners ----------------------------------------------------------

    @Test
    fun `a member or admin who owns nothing can close their account`() {
        closeAccount(member).andExpect(status().isNoContent)
        closeAccount(admin).andExpect(status().isNoContent)

        assertThat(deletedAtOf(member)).isNotNull()
        assertThat(deletedAtOf(admin)).isNotNull()
    }

    @Test
    fun `a closed non-owner keeps their membership row but disappears from the member list`() {
        closeAccount(member).andExpect(status().isNoContent)

        // The documented decision: the row survives, because it is already inert and
        // deleting it would destroy the record of who was in the organization.
        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)

        // ...and is invisible to everyone, which is existing behaviour, unchanged.
        val body = getRequest("/api/organizations/$organizationId/members", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andReturn().response.contentAsString
        assertThat(body).doesNotContain("member@example.com")
    }

    @Test
    fun `the surviving membership row grants nothing`() {
        closeAccount(member).andExpect(status().isNoContent)

        // Same access token, still correctly signed and unexpired. The account check is
        // what stops it - the membership row does not resurrect it.
        getRequest("/api/organizations/$organizationId", member.accessToken).andExpect(status().isForbidden)
        getRequest("/api/organizations", member.accessToken).andExpect(status().isForbidden)
        getRequest("/api/users/me", member.accessToken).andExpect(status().isForbidden)
    }

    @Test
    fun `an admin can still remove a closed member's row if they want it gone`() {
        closeAccount(member).andExpect(status().isNoContent)

        deleteRequest("/api/organizations/$organizationId/members/${member.id}", owner.accessToken)
            .andExpect(status().isNoContent)

        assertThat(roleOf(organizationId, member)).isNull()
    }

    // --- what closure does to sessions ------------------------------------------------------

    @Test
    fun `closing an account revokes its refresh tokens`() {
        val refreshToken = member.tokens.refreshToken

        closeAccount(member).andExpect(status().isNoContent)

        // Without this, refresh would keep minting access tokens forever: rotate() looks
        // the token up and never consults the user row.
        postJson("/api/auth/refresh", """{"refreshToken":"$refreshToken"}""")
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `a closed account cannot log back in`() {
        closeAccount(member).andExpect(status().isNoContent)

        postJson("/api/auth/login", """{"email":"member@example.com","password":"$DEFAULT_PASSWORD"}""")
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `closing twice is refused the second time`() {
        closeAccount(member).andExpect(status().isNoContent)
        closeAccount(member).andExpect(status().isForbidden)

        assertThat(deletedAtOf(member)).isNotNull()
    }

    @Test
    fun `closing an account requires authentication`() {
        deleteRequest("/api/users/me").andExpect(status().isUnauthorized)

        assertThat(deletedAtOf(member)).isNull()
    }

    @Test
    fun `closure only ever closes the caller's own account`() {
        closeAccount(member).andExpect(status().isNoContent)

        // There is no id in the request to point somewhere else, and nobody else moved.
        assertThat(deletedAtOf(owner)).isNull()
        assertThat(deletedAtOf(admin)).isNull()
    }

}
