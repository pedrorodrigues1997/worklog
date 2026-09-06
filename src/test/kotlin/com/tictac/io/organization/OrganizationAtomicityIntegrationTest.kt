package com.tictac.io.organization

import com.tictac.io.support.OrganizationApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The two multi-statement writes in this domain, and the guarantee that each is all-or-nothing.
 *
 * Organization creation is two INSERTs, and either both land or neither does.
 *
 * The half that matters is "organization without an owner": nobody could ever be granted a
 * role in it, because granting roles requires already being a member, so the tenant would
 * be permanently stranded with no way to fix it through the API. Asserting that both rows
 * exist after a success does not test this - the interesting case is the failure - so the
 * membership write is made to fail and the organization row is checked to be gone.
 *
 * The spy gives this class its own Spring context and therefore its own container, which
 * is a real cost. It earns it the same way the rate-limit and OAuth wiring tests do: the
 * behaviour is a property of the transaction boundary, and nothing that shares a context
 * can force the second statement to fail.
 */
@DisplayName("Organization writes are atomic")
class OrganizationAtomicityIntegrationTest : OrganizationApiTest() {

    @MockitoSpyBean
    private lateinit var memberRepositorySpy: OrganizationMemberRepository

    @Test
    fun `a failure creating the owner membership rolls the organization back`() {
        val pedro = newUser()

        doThrow(DataIntegrityViolationException("membership insert failed"))
            .whenever(memberRepositorySpy).saveAndFlush(any<OrganizationMember>())

        postJson("/api/organizations", """{"name":"Acme Consulting"}""", pedro.accessToken)
            .andExpect(status().is5xxServerError)

        // The organization INSERT was flushed to the database before the membership write
        // failed, so this is a real rollback rather than a statement that never ran.
        assertThat(organizationRepository.count()).isZero()
        assertThat(organizationMemberRepository.count()).isZero()

        // ...and the caller is left with nothing dangling.
        assertThat(organizationMemberRepository.findOrganizationsForUser(pedro.id)).isEmpty()
    }

    @Test
    fun `a failure promoting the new owner rolls the demotion back`() {
        val owner = newUser("owner@example.com")
        val target = newUser("target@example.com")
        val organizationId = createOrganization(owner, "Acme")
        organizationMemberRepository.saveAndFlush(
            OrganizationMember(organizationId, target.id, OrganizationRole.MEMBER),
        )

        // Transfer writes the demote first and the promote second - the order the partial
        // unique index requires. Fail only the promote, matched on whose row it is; the
        // demote is left unstubbed and so runs for real. That puts the failure exactly in
        // the window where the organization has no owner at all.
        doThrow(DataIntegrityViolationException("promote failed"))
            .whenever(memberRepositorySpy)
            .saveAndFlush(argThat<OrganizationMember> { userId == target.id })

        postJson(
            "/api/organizations/$organizationId/transfer-ownership",
            """{"userId":"${target.id}"}""",
            owner.accessToken,
        ).andExpect(status().is5xxServerError)

        // The demotion had already reached the database. If it were not rolled back, this
        // organization would now have zero owners and be impossible to administer.
        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(organizationId, target)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER))
            .isEqualTo(1)
    }

    @Test
    fun `a failure removing the organization membership rolls back the project cleanup`() {
        val owner = newUser("cascade-owner@example.com")
        val alice = newUser("cascade-alice@example.com")
        val organizationId = createOrganization(owner, "Acme")
        organizationMemberRepository.saveAndFlush(
            OrganizationMember(organizationId, alice.id, OrganizationRole.MEMBER),
        )
        val projectId = createProject(organizationId, owner, "Website Redesign")
        assignToProject(organizationId, projectId, alice, owner)

        // Removal deletes the project assignments first and the membership second. Fail the
        // second, and the assignments must come back with it - otherwise Alice would still
        // be an Acme member while silently dropped from every Acme project.
        doThrow(DataIntegrityViolationException("membership delete failed"))
            .whenever(memberRepositorySpy).delete(any<OrganizationMember>())

        deleteRequest("/api/organizations/$organizationId/members/${alice.id}", owner.accessToken)
            .andExpect(status().is5xxServerError)

        assertThat(roleOf(organizationId, alice)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(isAssigned(projectId, alice)).isTrue()
    }

    @Test
    fun `creation still works once the membership write succeeds`() {
        // Guards against the spy above silently leaking into the rest of the class and
        // making the failure assertions vacuous.
        val pedro = newUser()
        val organizationId = createOrganization(pedro, "Acme Consulting")

        assertThat(organizationRepository.findById(organizationId)).isPresent
        assertThat(roleOf(organizationId, pedro)).isEqualTo(OrganizationRole.OWNER)
    }
}
