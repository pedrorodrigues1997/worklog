package com.tictac.io.project

import com.jayway.jsonpath.JsonPath
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
 * The cross-tenant matrix for projects.
 *
 * User A is the OWNER of organization A throughout - fully authenticated, and an
 * administrator of a real tenant. What is under test is whether that standing leaks sideways
 * into organization B. Every project endpoint appears below, because the failure this guards
 * against is a *new* route that forgets to scope by organization.
 *
 * Two shapes of attack are covered, and the second is the one that matters most:
 *
 * 1. addressing organization B directly - refused by [com.tictac.io.organization.OrganizationAccess];
 * 2. addressing organization *A* while passing project B's id - which would succeed against
 *    any implementation that resolves a project by id alone and checks the organization
 *    afterwards, or not at all.
 */
@DisplayName("Tenant isolation: projects cannot be reached across organizations")
class ProjectTenantIsolationIntegrationTest : OrganizationApiTest() {

    private lateinit var userA: TestUser
    private lateinit var userB: TestUser
    private lateinit var memberOfB: TestUser
    private var organizationA = UUID.randomUUID()
    private var organizationB = UUID.randomUUID()
    private var projectA = UUID.randomUUID()
    private var projectB = UUID.randomUUID()

    @BeforeEach
    fun createTwoTenants() {
        userA = newUser("a@example.com")
        userB = newUser("b@example.com")
        memberOfB = newUser("member-of-b@example.com")

        organizationA = createOrganization(userA, "Organization A")
        organizationB = createOrganization(userB, "Organization B")
        addMember(organizationB, memberOfB, OrganizationRole.MEMBER)

        projectA = createProject(organizationA, userA, "Project A")
        projectB = createProject(organizationB, userB, "Project B")
        assignToProject(organizationB, projectB, memberOfB, userB)
    }

    /** Addressed through organization B, which user A does not belong to. */
    private fun viaB(suffix: String = "") = "/api/organizations/$organizationB/projects/$projectB$suffix"

    /** Addressed through user A's *own* organization, carrying organization B's project id. */
    private fun viaOwnOrganization(suffix: String = "") =
        "/api/organizations/$organizationA/projects/$projectB$suffix"

    @Test
    fun `user A cannot list organization B's projects`() {
        val body = getRequest("/api/organizations/$organizationB/projects", userA.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("Project B")
    }

    @Test
    fun `user A cannot view project B`() {
        listOf(viaB(), viaOwnOrganization()).forEach { path ->
            val body = getRequest(path, userA.accessToken)
                .andExpect(status().isNotFound)
                .andReturn().response.contentAsString

            assertThat(body).doesNotContain("Project B")
        }
    }

    @Test
    fun `user A cannot modify project B`() {
        listOf(viaB(), viaOwnOrganization()).forEach { path ->
            patchJson(path, """{"name":"Taken Over","isActive":false}""", userA.accessToken)
                .andExpect(status().isNotFound)
        }

        val project = projectRepository.findById(projectB).orElseThrow()
        assertThat(project.name).isEqualTo("Project B")
        assertThat(project.isActive).isTrue()
    }

    @Test
    fun `user A cannot list project B's members`() {
        listOf(viaB("/members"), viaOwnOrganization("/members")).forEach { path ->
            val body = getRequest(path, userA.accessToken)
                .andExpect(status().isNotFound)
                .andReturn().response.contentAsString

            assertThat(body).doesNotContain("member-of-b@example.com")
            assertThat(body).doesNotContain("b@example.com")
        }
    }

    @Test
    fun `user A cannot add members to project B`() {
        // Both a user of B, and themselves - neither may be assigned.
        listOf(memberOfB.id, userA.id).forEach { target ->
            listOf(viaB("/members"), viaOwnOrganization("/members")).forEach { path ->
                postJson(path, """{"userId":"$target"}""", userA.accessToken)
                    .andExpect(status().isNotFound)
            }
        }

        assertThat(projectMemberRepository.countByProjectId(projectB)).isEqualTo(1)
        assertThat(isAssigned(projectB, userA)).isFalse()
    }

    @Test
    fun `user A cannot remove members from project B`() {
        listOf(viaB("/members/${memberOfB.id}"), viaOwnOrganization("/members/${memberOfB.id}"))
            .forEach { path -> deleteRequest(path, userA.accessToken).andExpect(status().isNotFound) }

        assertThat(isAssigned(projectB, memberOfB)).isTrue()
    }

    @Test
    fun `user A cannot create a project inside organization B`() {
        postJson("/api/organizations/$organizationB/projects", """{"name":"Planted"}""", userA.accessToken)
            .andExpect(status().isNotFound)

        assertThat(projectRepository.findAllByOrganizationIdOrderByCreatedAtAsc(organizationB))
            .extracting<String> { it.name }
            .containsExactly("Project B")
    }

    @Test
    fun `user A cannot assign a user from organization B to their own project`() {
        // The mirror image: a valid user id, a project the caller genuinely administers,
        // and the two belong to different companies.
        postJson(
            "/api/organizations/$organizationA/projects/$projectA/members",
            """{"userId":"${memberOfB.id}"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(projectMemberRepository.countByProjectId(projectA)).isZero()
        assertThat(isAssigned(projectA, memberOfB)).isFalse()
    }

    @Test
    fun `a foreign project id is indistinguishable from one that does not exist`() {
        val foreign = getRequest(viaOwnOrganization(), userA.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        val imaginary = getRequest(
            "/api/organizations/$organizationA/projects/${UUID.randomUUID()}",
            userA.accessToken,
        )
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        // Everything the server chose is identical; only RFC 9457's `instance` differs, and
        // that just echoes the id the caller supplied.
        listOf("$.status", "$.title", "$.detail").forEach { field ->
            assertThat(JsonPath.read<Any>(foreign, field)).isEqualTo(JsonPath.read<Any>(imaginary, field))
        }
    }

    @Test
    fun `organization B's own people are unaffected throughout`() {
        getRequest(viaB(), userB.accessToken).andExpect(status().isOk)
        getRequest(viaB(), memberOfB.accessToken).andExpect(status().isOk)
        getRequest(viaB("/members"), memberOfB.accessToken).andExpect(status().isOk)
    }
}
