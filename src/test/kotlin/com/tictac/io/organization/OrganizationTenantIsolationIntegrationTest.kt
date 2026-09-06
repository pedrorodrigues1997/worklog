package com.tictac.io.organization

import com.jayway.jsonpath.JsonPath
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * The cross-tenant matrix.
 *
 * User A is authenticated the whole way through - every request here carries a valid,
 * unexpired access token. What is being tested is the second question: whether being
 * *someone* is enough to act on an organization they have no membership in. Every endpoint
 * that takes an organization id has to answer no, so every endpoint appears below; the
 * failure this guards against is a new route that forgets the check.
 *
 * The expected status is 404, not 403. A stranger must not be able to tell an organization
 * that exists from one that does not - see OrganizationNotFoundException - so an id leaked
 * into a URL or a support ticket reveals nothing about whether it is real.
 */
@DisplayName("Tenant isolation: an authenticated user cannot reach another organization")
class OrganizationTenantIsolationIntegrationTest : OrganizationApiTest() {

    private lateinit var userA: TestUser
    private lateinit var userB: TestUser
    private lateinit var memberOfB: TestUser
    private lateinit var organizationA: UUID
    private lateinit var organizationB: UUID

    @BeforeEach
    fun createTwoTenants() {
        userA = newUser("a@example.com")
        userB = newUser("b@example.com")
        memberOfB = newUser("member-of-b@example.com")

        organizationA = createOrganization(userA, "Organization A")
        organizationB = createOrganization(userB, "Organization B")
        addMember(organizationB, memberOfB, OrganizationRole.MEMBER)
    }

    @Test
    fun `user A cannot read organization B`() {
        getRequest("/api/organizations/$organizationB", userA.accessToken)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `user A cannot update organization B`() {
        patchJson("/api/organizations/$organizationB", """{"name":"Taken Over"}""", userA.accessToken)
            .andExpect(status().isNotFound)

        assertThat(organizationRepository.findById(organizationB).orElseThrow().name)
            .isEqualTo("Organization B")
    }

    @Test
    fun `user A cannot delete organization B`() {
        deleteRequest("/api/organizations/$organizationB", userA.accessToken)
            .andExpect(status().isNotFound)

        assertThat(organizationRepository.findById(organizationB).orElseThrow().deletedAt).isNull()
    }

    @Test
    fun `user A cannot list organization B's members`() {
        val response = getRequest("/api/organizations/$organizationB/members", userA.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString

        // Not merely refused: nothing about B's people is in the body either.
        assertThat(response).doesNotContain("b@example.com")
        assertThat(response).doesNotContain("member-of-b@example.com")
    }

    @Test
    fun `user A cannot change the role of organization B's members`() {
        patchJson(
            "/api/organizations/$organizationB/members/${memberOfB.id}",
            """{"role":"ADMIN"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(roleOf(organizationB, memberOfB)).isEqualTo(OrganizationRole.MEMBER)
    }

    @Test
    fun `user A cannot remove organization B's members`() {
        deleteRequest("/api/organizations/$organizationB/members/${memberOfB.id}", userA.accessToken)
            .andExpect(status().isNotFound)

        assertThat(roleOf(organizationB, memberOfB)).isEqualTo(OrganizationRole.MEMBER)
    }

    @Test
    fun `user A cannot add themselves to organization B through a role change`() {
        // The obvious escalation: name yourself in someone else's organization.
        patchJson(
            "/api/organizations/$organizationB/members/${userA.id}",
            """{"role":"OWNER"}""",
            userA.accessToken,
        ).andExpect(status().isNotFound)

        assertThat(roleOf(organizationB, userA)).isNull()
    }

    @Test
    fun `user A cannot remove organization B's owner`() {
        deleteRequest("/api/organizations/$organizationB/members/${userB.id}", userA.accessToken)
            .andExpect(status().isNotFound)

        assertThat(roleOf(organizationB, userB)).isEqualTo(OrganizationRole.OWNER)
    }

    @Test
    fun `organization B never appears in user A's listing`() {
        val body = getRequest("/api/organizations", userA.accessToken)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains(organizationA.toString())
        assertThat(body).doesNotContain(organizationB.toString())
        assertThat(body).doesNotContain("Organization B")
    }

    @Test
    fun `an organization the caller does not belong to is indistinguishable from one that does not exist`() {
        val imaginaryId = UUID.randomUUID()

        val realButForeign = getRequest("/api/organizations/$organizationB", userA.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response

        val imaginary = getRequest("/api/organizations/$imaginaryId", userA.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response

        assertThat(realButForeign.status).isEqualTo(imaginary.status)
        assertThat(realButForeign.contentType).isEqualTo(imaginary.contentType)

        // Everything the *server* chose is identical, so the response is not an oracle for
        // whether an organization id belongs to a real tenant.
        listOf("$.status", "$.title", "$.detail").forEach { field ->
            assertThat(JsonPath.read<Any>(realButForeign.contentAsString, field))
                .isEqualTo(JsonPath.read<Any>(imaginary.contentAsString, field))
        }

        // The one field that does differ is RFC 9457's `instance`, which echoes the request
        // path. It can only ever repeat the id the caller just supplied, so it tells them
        // nothing they did not already know.
        assertThat(JsonPath.read<String>(realButForeign.contentAsString, "$.instance"))
            .isEqualTo("/api/organizations/$organizationB")
        assertThat(JsonPath.read<String>(imaginary.contentAsString, "$.instance"))
            .isEqualTo("/api/organizations/$imaginaryId")
    }

    @Test
    fun `membership in one organization grants nothing in another`() {
        // memberOfB is a legitimate member somewhere - that must not generalise.
        getRequest("/api/organizations/$organizationA", memberOfB.accessToken)
            .andExpect(status().isNotFound)

        getRequest("/api/organizations/$organizationA/members", memberOfB.accessToken)
            .andExpect(status().isNotFound)
    }
}
