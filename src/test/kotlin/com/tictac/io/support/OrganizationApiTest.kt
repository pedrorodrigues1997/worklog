package com.tictac.io.support

import com.jayway.jsonpath.JsonPath
import com.tictac.io.organization.OrganizationMember
import com.tictac.io.organization.OrganizationRole
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** An account plus the tokens it signed in with, so a test can act as that person. */
data class TestUser(val id: UUID, val email: String, val tokens: TokenPair) {
    val accessToken: String get() = tokens.accessToken
}

/**
 * Organization helpers on top of [AuthenticatedApiTest]. Adds no bean overrides, so tests
 * extending this still share the one Spring context and container.
 */
abstract class OrganizationApiTest : AuthenticatedApiTest() {

    private var accountCounter = 0

    /** A fresh registered-and-signed-in account. Emails are unique per call. */
    protected fun newUser(email: String = "user${accountCounter++}@example.com"): TestUser {
        val (id, tokens) = registerAndLogin(email = email)
        return TestUser(id, email, tokens)
    }

    /** Creates an organization through the real endpoint; [owner] becomes its OWNER. */
    protected fun createOrganization(owner: TestUser, name: String = "Acme Consulting"): UUID {
        val body = postJson("/api/organizations", """{"name":"$name"}""", owner.accessToken)
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        return UUID.fromString(JsonPath.read(body, "$.id"))
    }

    /**
     * Seeds a membership directly.
     *
     * There is no API for this on purpose - joining an organization will happen by
     * accepting an invitation, and that feature does not exist yet - so tests that need an
     * ADMIN or a second MEMBER in place have to write the row the invitation flow will
     * eventually write. Everything actually under test (role checks, tenant isolation)
     * still goes through the real endpoints.
     */
    protected fun addMember(organizationId: UUID, user: TestUser, role: OrganizationRole): OrganizationMember =
        organizationMemberRepository.saveAndFlush(
            OrganizationMember(organizationId = organizationId, userId = user.id, role = role),
        )

    protected fun roleOf(organizationId: UUID, user: TestUser): OrganizationRole? =
        organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, user.id)?.role
}
