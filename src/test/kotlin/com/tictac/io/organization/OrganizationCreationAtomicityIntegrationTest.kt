package com.tictac.io.organization

import com.tictac.io.support.OrganizationApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
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
@DisplayName("Organization creation is atomic")
class OrganizationCreationAtomicityIntegrationTest : OrganizationApiTest() {

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
    fun `creation still works once the membership write succeeds`() {
        // Guards against the spy above silently leaking into the rest of the class and
        // making the failure assertions vacuous.
        val pedro = newUser()
        val organizationId = createOrganization(pedro, "Acme Consulting")

        assertThat(organizationRepository.findById(organizationId)).isPresent
        assertThat(roleOf(organizationId, pedro)).isEqualTo(OrganizationRole.OWNER)
    }
}
