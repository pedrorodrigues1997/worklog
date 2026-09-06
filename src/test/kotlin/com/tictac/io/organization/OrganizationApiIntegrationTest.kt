package com.tictac.io.organization

import com.jayway.jsonpath.JsonPath
import com.tictac.io.support.OrganizationApiTest
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@DisplayName("Organization API: creation, listing and reading")
class OrganizationApiIntegrationTest : OrganizationApiTest() {

    @Test
    fun `an authenticated user can create an organization and becomes its owner`() {
        val pedro = newUser()

        val body = postJson("/api/organizations", """{"name":"Acme Consulting"}""", pedro.accessToken)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Acme Consulting"))
            .andExpect(jsonPath("$.role").value("OWNER"))
            .andExpect(jsonPath("$.id").isNotEmpty)
            .andExpect(jsonPath("$.createdAt").isNotEmpty)
            .andReturn().response.contentAsString

        val organizationId = UUID.fromString(JsonPath.read(body, "$.id"))

        // Both rows exist, and they refer to each other. This is the state the create is
        // required to produce as a unit; see OrganizationCreationAtomicityIntegrationTest
        // for the proof that a half of it can never be left behind.
        val organization = organizationRepository.findById(organizationId).orElseThrow()
        assertThat(organization.name).isEqualTo("Acme Consulting")
        assertThat(organization.deletedAt).isNull()

        val membership = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, pedro.id)
        assertThat(membership).isNotNull
        assertThat(membership!!.role).isEqualTo(OrganizationRole.OWNER)
        assertThat(organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER))
            .isEqualTo(1)
    }

    @Test
    fun `an unauthenticated user cannot create an organization`() {
        postJson("/api/organizations", """{"name":"Acme Consulting"}""")
            .andExpect(status().isUnauthorized)

        assertThat(organizationRepository.count()).isZero()
    }

    @Test
    fun `an expired or forged token cannot create an organization`() {
        postJson("/api/organizations", """{"name":"Acme"}""", accessToken = "not-a-real-token")
            .andExpect(status().isUnauthorized)

        assertThat(organizationRepository.count()).isZero()
    }

    @Test
    fun `the organization name is required and bounded`() {
        val pedro = newUser()

        listOf("""{"name":""}""", """{"name":"   "}""", """{}""")
            .forEach { postJson("/api/organizations", it, pedro.accessToken).andExpect(status().isBadRequest) }

        val tooLong = "a".repeat(Organization.MAX_NAME_LENGTH + 1)
        postJson("/api/organizations", """{"name":"$tooLong"}""", pedro.accessToken)
            .andExpect(status().isBadRequest)

        assertThat(organizationRepository.count()).isZero()
    }

    @Test
    fun `the name is trimmed rather than stored with the caller's whitespace`() {
        val pedro = newUser()

        postJson("/api/organizations", """{"name":"  Acme Consulting  "}""", pedro.accessToken)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Acme Consulting"))
    }

    @Test
    fun `the creator is taken from the token, not from the request body`() {
        val pedro = newUser()
        val john = newUser()

        // A body naming someone else changes nothing: userId is not part of the contract
        // and there is no code path that reads one.
        val body = postJson(
            "/api/organizations",
            """{"name":"Acme","userId":"${john.id}","ownerId":"${john.id}","role":"MEMBER"}""",
            pedro.accessToken,
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.role").value("OWNER"))
            .andReturn().response.contentAsString

        val organizationId = UUID.fromString(JsonPath.read(body, "$.id"))

        assertThat(roleOf(organizationId, pedro)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(organizationId, john)).isNull()
    }

    @Test
    fun `a user sees the organizations they belong to, with the role they hold in each`() {
        val pedro = newUser()
        val john = newUser()

        val acme = createOrganization(pedro, "Acme")
        val other = createOrganization(john, "Other Company")
        addMember(other, pedro, OrganizationRole.MEMBER)

        val body = getRequest("/api/organizations", pedro.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andReturn().response.contentAsString

        // Keyed by id rather than by position: the ordering is by a creation timestamp
        // that two organizations created in the same microsecond could share.
        assertThat(rolesById(body)).containsExactlyInAnyOrderEntriesOf(
            mapOf(acme.toString() to "OWNER", other.toString() to "MEMBER"),
        )
        assertThat(JsonPath.read<List<String>>(body, "$[*].name")).containsExactlyInAnyOrder("Acme", "Other Company")
    }

    @Test
    fun `a user does not see organizations they do not belong to`() {
        val pedro = newUser()
        val john = newUser()
        createOrganization(john, "Other Company")

        getRequest("/api/organizations", pedro.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(0)))

        // The organization does exist - the empty list is a tenant filter, not an empty
        // table, which is the failure mode this asserts against.
        assertThat(organizationRepository.count()).isEqualTo(1)
    }

    @Test
    fun `listing requires authentication`() {
        getRequest("/api/organizations").andExpect(status().isUnauthorized)
    }

    @Test
    fun `a member can read the organization they belong to`() {
        val pedro = newUser()
        val john = newUser()
        val acme = createOrganization(pedro, "Acme")
        addMember(acme, john, OrganizationRole.MEMBER)

        getRequest("/api/organizations/$acme", pedro.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(acme.toString()))
            .andExpect(jsonPath("$.role").value("OWNER"))

        // The same organization, read by someone with a different standing in it.
        getRequest("/api/organizations/$acme", john.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("MEMBER"))
    }

    @Test
    fun `an organization that does not exist is not found`() {
        val pedro = newUser()

        getRequest("/api/organizations/${UUID.randomUUID()}", pedro.accessToken)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `an organization id that is not a uuid is rejected as a bad request`() {
        val pedro = newUser()

        getRequest("/api/organizations/not-a-uuid", pedro.accessToken)
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `an owner can rename the organization`() {
        val pedro = newUser()
        val acme = createOrganization(pedro, "Acme")

        patchJson("/api/organizations/$acme", """{"name":"Acme Consulting"}""", pedro.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Acme Consulting"))
            .andExpect(jsonPath("$.role").value("OWNER"))

        assertThat(organizationRepository.findById(acme).orElseThrow().name).isEqualTo("Acme Consulting")
    }

    @Test
    fun `renaming validates the new name`() {
        val pedro = newUser()
        val acme = createOrganization(pedro, "Acme")

        listOf("""{"name":""}""", """{}""", """{"name":"${"a".repeat(Organization.MAX_NAME_LENGTH + 1)}"}""")
            .forEach {
                patchJson("/api/organizations/$acme", it, pedro.accessToken).andExpect(status().isBadRequest)
            }

        assertThat(organizationRepository.findById(acme).orElseThrow().name).isEqualTo("Acme")
    }

    @Test
    fun `a user belonging to several organizations gets all of them with the correct roles`() {
        val pedro = newUser()
        val john = newUser()

        val first = createOrganization(pedro, "First")
        val second = createOrganization(john, "Second")
        val third = createOrganization(john, "Third")
        addMember(second, pedro, OrganizationRole.MEMBER)
        addMember(third, pedro, OrganizationRole.ADMIN)

        val body = getRequest("/api/organizations", pedro.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(3)))
            .andReturn().response.contentAsString

        assertThat(rolesById(body)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                first.toString() to "OWNER",
                second.toString() to "MEMBER",
                third.toString() to "ADMIN",
            ),
        )
    }

    private fun rolesById(body: String): Map<String, String> =
        JsonPath.read<List<String>>(body, "$[*].id")
            .zip(JsonPath.read<List<String>>(body, "$[*].role"))
            .toMap()

    @Test
    fun `one user can own several organizations at once`() {
        val pedro = newUser()
        val first = createOrganization(pedro, "First")
        val second = createOrganization(pedro, "Second")

        assertThat(first).isNotEqualTo(second)
        assertThat(roleOf(first, pedro)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(second, pedro)).isEqualTo(OrganizationRole.OWNER)
    }
}
