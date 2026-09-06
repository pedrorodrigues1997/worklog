package com.tictac.io.organization

import com.jayway.jsonpath.JsonPath
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Role enforcement inside a single organization.
 *
 * Everyone here is a legitimate member, so 403 rather than 404 is the right refusal: the
 * caller can already see the organization exists, and saying so costs nothing.
 */
@DisplayName("Organization roles: what each role may and may not do")
class OrganizationRoleAuthorizationIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var secondAdmin: TestUser
    private lateinit var member: TestUser
    private var organizationId = java.util.UUID.randomUUID()

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

    // --- reading -----------------------------------------------------------------

    @Test
    fun `every role can read the organization and its members`() {
        listOf(owner, admin, member).forEach { user ->
            getRequest("/api/organizations/$organizationId", user.accessToken)
                .andExpect(status().isOk)

            getRequest("/api/organizations/$organizationId/members", user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(4)))
        }
    }

    @Test
    fun `the member list returns safe user fields and no authentication secrets`() {
        val body = getRequest("/api/organizations/$organizationId/members", member.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(4)))
            .andExpect(jsonPath("$[*].firstName", everyItem(equalTo("John"))))
            .andExpect(jsonPath("$[*].lastName", everyItem(equalTo("Smith"))))
            .andExpect(jsonPath("$[*].joinedAt", everyItem(notNullValue())))
            .andReturn().response.contentAsString

        assertThat(rolesByEmail(body)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "owner@example.com" to "OWNER",
                "admin@example.com" to "ADMIN",
                "second-admin@example.com" to "ADMIN",
                "member@example.com" to "MEMBER",
            ),
        )
        assertThat(JsonPath.read<List<String>>(body, "$[*].userId")).contains(owner.id.toString())

        // The hash itself, and any field that could ever carry one.
        assertThat(body).doesNotContain("argon2")
        assertThat(body).doesNotContain("passwordHash")
        assertThat(body).doesNotContain("password_hash")
        assertThat(body).doesNotContain("password")
    }

    // --- organization settings ----------------------------------------------------

    @Test
    fun `owner and admin can rename the organization`() {
        patchJson("/api/organizations/$organizationId", """{"name":"Renamed By Owner"}""", owner.accessToken)
            .andExpect(status().isOk)

        patchJson("/api/organizations/$organizationId", """{"name":"Renamed By Admin"}""", admin.accessToken)
            .andExpect(status().isOk)

        assertThat(organizationRepository.findById(organizationId).orElseThrow().name)
            .isEqualTo("Renamed By Admin")
    }

    @Test
    fun `a member cannot rename the organization`() {
        patchJson("/api/organizations/$organizationId", """{"name":"Renamed By Member"}""", member.accessToken)
            .andExpect(status().isForbidden)

        assertThat(organizationRepository.findById(organizationId).orElseThrow().name).isEqualTo("Acme")
    }

    @Test
    fun `only the owner can delete the organization`() {
        deleteRequest("/api/organizations/$organizationId", member.accessToken)
            .andExpect(status().isForbidden)
        deleteRequest("/api/organizations/$organizationId", admin.accessToken)
            .andExpect(status().isForbidden)

        assertThat(organizationRepository.findById(organizationId).orElseThrow().deletedAt).isNull()

        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)
    }

    // --- role changes -------------------------------------------------------------

    @Test
    fun `the owner can promote a member to admin and demote them again`() {
        patchJson(
            "/api/organizations/$organizationId/members/${member.id}",
            """{"role":"ADMIN"}""",
            owner.accessToken,
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(member.id.toString()))
            .andExpect(jsonPath("$.role").value("ADMIN"))

        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.ADMIN)

        patchJson(
            "/api/organizations/$organizationId/members/${member.id}",
            """{"role":"MEMBER"}""",
            owner.accessToken,
        ).andExpect(status().isOk)

        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
    }

    @Test
    fun `an admin can promote a member`() {
        patchJson(
            "/api/organizations/$organizationId/members/${member.id}",
            """{"role":"ADMIN"}""",
            admin.accessToken,
        ).andExpect(status().isOk)

        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.ADMIN)
    }

    @Test
    fun `a member cannot change anybody's role, including their own`() {
        listOf(member.id to "ADMIN", owner.id to "MEMBER", admin.id to "MEMBER").forEach { (target, role) ->
            patchJson(
                "/api/organizations/$organizationId/members/$target",
                """{"role":"$role"}""",
                member.accessToken,
            ).andExpect(status().isForbidden)
        }

        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(organizationId, admin)).isEqualTo(OrganizationRole.ADMIN)
    }

    @Test
    fun `an admin cannot demote a fellow admin`() {
        patchJson(
            "/api/organizations/$organizationId/members/${secondAdmin.id}",
            """{"role":"MEMBER"}""",
            admin.accessToken,
        ).andExpect(status().isForbidden)

        assertThat(roleOf(organizationId, secondAdmin)).isEqualTo(OrganizationRole.ADMIN)
    }

    @Test
    fun `an admin cannot change their own role`() {
        patchJson(
            "/api/organizations/$organizationId/members/${admin.id}",
            """{"role":"OWNER"}""",
            admin.accessToken,
        ).andExpect(status().isForbidden)

        patchJson(
            "/api/organizations/$organizationId/members/${admin.id}",
            """{"role":"MEMBER"}""",
            admin.accessToken,
        ).andExpect(status().isForbidden)

        assertThat(roleOf(organizationId, admin)).isEqualTo(OrganizationRole.ADMIN)
    }

    @Test
    fun `nobody can be promoted to owner, not even by the owner`() {
        // Ownership transfer has to demote the current owner in the same step, so it is a
        // separate operation rather than something that falls out of a role edit. Until it
        // exists, OWNER is not an assignable value at all - and the partial unique index
        // in V6 would refuse a second owner even if this check were removed.
        listOf(owner, admin).forEach { caller ->
            patchJson(
                "/api/organizations/$organizationId/members/${member.id}",
                """{"role":"OWNER"}""",
                caller.accessToken,
            ).andExpect(status().isForbidden)
        }

        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER))
            .isEqualTo(1)
    }

    @Test
    fun `the owner cannot be demoted, so the organization always has one`() {
        listOf(owner, admin).forEach { caller ->
            patchJson(
                "/api/organizations/$organizationId/members/${owner.id}",
                """{"role":"MEMBER"}""",
                caller.accessToken,
            ).andExpect(status().isForbidden)
        }

        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
    }

    @Test
    fun `an unknown role value is rejected as a bad request`() {
        listOf("""{"role":"SUPERUSER"}""", """{"role":""}""", """{"role":null}""", """{}""").forEach { body ->
            patchJson("/api/organizations/$organizationId/members/${member.id}", body, owner.accessToken)
                .andExpect(status().isBadRequest)
        }

        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
    }

    @Test
    fun `changing the role of someone who is not a member is a not found`() {
        val stranger = newUser("stranger@example.com")

        patchJson(
            "/api/organizations/$organizationId/members/${stranger.id}",
            """{"role":"ADMIN"}""",
            owner.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(roleOf(organizationId, stranger)).isNull()
    }

    /** Keyed by email rather than by position: the list is ordered by a timestamp that two
     *  memberships created in the same microsecond could share. */
    private fun rolesByEmail(body: String): Map<String, String> =
        JsonPath.read<List<String>>(body, "$[*].email")
            .zip(JsonPath.read<List<String>>(body, "$[*].role"))
            .toMap()
}
