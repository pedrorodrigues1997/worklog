package com.tictac.io.organization

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

@DisplayName("Organization soft deletion")
class OrganizationSoftDeleteIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var member: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        member = newUser("member@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, member, OrganizationRole.MEMBER)
    }

    @Test
    fun `the owner soft deletes the organization rather than destroying it`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        val organization = organizationRepository.findById(organizationId).orElseThrow()
        assertThat(organization.deletedAt).isNotNull()
        assertThat(organization.name).isEqualTo("Acme")
    }

    @Test
    fun `a deleted organization disappears from every member's listing`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        listOf(owner, member).forEach { user ->
            getRequest("/api/organizations", user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(0)))
        }
    }

    @Test
    fun `a deleted organization cannot be reached by anyone, including its owner`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        listOf(owner, member).forEach { user ->
            getRequest("/api/organizations/$organizationId", user.accessToken)
                .andExpect(status().isNotFound)
            patchJson("/api/organizations/$organizationId", """{"name":"Revived"}""", user.accessToken)
                .andExpect(status().isNotFound)
            getRequest("/api/organizations/$organizationId/members", user.accessToken)
                .andExpect(status().isNotFound)
            patchJson(
                "/api/organizations/$organizationId/members/${member.id}",
                """{"role":"ADMIN"}""",
                user.accessToken,
            ).andExpect(status().isNotFound)
            deleteRequest("/api/organizations/$organizationId/members/${member.id}", user.accessToken)
                .andExpect(status().isNotFound)
        }

        assertThat(organizationRepository.findById(organizationId).orElseThrow().name).isEqualTo("Acme")
    }

    @Test
    fun `deleting twice is a not found the second time`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `deleting the organization destroys none of its data`() {
        val deletedAtBefore = organizationRepository.findById(organizationId).orElseThrow().deletedAt
        assertThat(deletedAtBefore).isNull()

        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        // Memberships survive: the organization is closed, not purged, and reopening it
        // must not require rebuilding the member list from nothing.
        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(userRepository.findById(member.id)).isPresent
    }

    @Test
    fun `deleting one organization leaves the caller's others alone`() {
        val second = createOrganization(owner, "Second")

        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        getRequest("/api/organizations", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].id").value(second.toString()))
    }
}
