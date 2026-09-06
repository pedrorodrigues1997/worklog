package com.tictac.io.organization

import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * An invitation is consumed once, whatever two requests do at the same instant.
 *
 * "Read the invitation, check it is unaccepted, write the membership" is not enough on its
 * own: two transactions can both pass the check before either commits. Acceptance therefore
 * takes a `SELECT ... FOR UPDATE` on the invitation row, and the `(organization_id, user_id)`
 * unique index stands behind it. This is where both claims get tested rather than asserted.
 *
 * The service is called directly rather than through MockMvc: each thread needs its own
 * transaction and its own security context, and `SecurityContextHolder` is thread-local.
 * Nothing is mocked - callers come from real access tokens decoded by the application's own
 * [JwtDecoder].
 */
@DisplayName("Invitation acceptance under contention")
class InvitationConcurrencyIntegrationTest : OrganizationApiTest() {

    @Autowired
    private lateinit var invitationAcceptanceService: InvitationAcceptanceService

    @Autowired
    private lateinit var jwtDecoder: JwtDecoder

    private lateinit var owner: TestUser
    private lateinit var bob: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        bob = newUser("bob@example.com")
        organizationId = createOrganization(owner, "Acme")
        // Seats bought up front: inviting is refused without a free seat, and this suite is
        // about the invitation's own concurrency rather than about capacity.
        subscribeOrganization(organizationId, licenses = 6)
    }

    @Test
    fun `two simultaneous acceptances of the same invitation produce one membership`() {
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            pool.invokeAll(listOf(acceptTask(barrier, token), acceptTask(barrier, token)), 30, TimeUnit.SECONDS)
                .map { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }

        // Exactly one wins. The loser fails either on the row lock finding an already-accepted
        // invitation, or on the membership index - both are correct refusals, and both roll
        // the whole transaction back.
        assertThat(results.filter { it.isSuccess })
            .describedAs("exactly one acceptance should succeed")
            .hasSize(1)

        assertThat(organizationMemberRepository.findAll().count { it.userId == bob.id }).isEqualTo(1)
        assertThat(organizationInvitationRepository.findAll().single().acceptedAt).isNotNull()
    }

    @Test
    fun `many simultaneous acceptances still produce one membership`() {
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        val attempts = 6
        val barrier = CyclicBarrier(attempts)
        val pool = Executors.newFixedThreadPool(attempts)
        val results = try {
            pool.invokeAll((0 until attempts).map { acceptTask(barrier, token) }, 30, TimeUnit.SECONDS)
                .map { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }

        assertThat(results.filter { it.isSuccess }).hasSize(1)
        assertThat(organizationMemberRepository.findAll().count { it.userId == bob.id }).isEqualTo(1)

        // Two members total - the founder and Bob - so nothing else slipped in either.
        assertThat(organizationMemberRepository.count()).isEqualTo(2)
    }

    @Test
    fun `sequential replay is refused by the invitation's own state`() {
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        postJson("/api/invitations/accept", """{"token":"$token"}""", bob.accessToken)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
        postJson("/api/invitations/accept", """{"token":"$token"}""", bob.accessToken)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict)

        assertThat(organizationMemberRepository.findAll().count { it.userId == bob.id }).isEqualTo(1)
    }

    @Test
    fun `the database refuses a duplicate membership even with the service out of the way`() {
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)
        postJson("/api/invitations/accept", """{"token":"$token"}""", bob.accessToken)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)

        // The last line of defence, asserted without going through any application code: the
        // (organization_id, user_id) index from V6 still stands, and it is what makes the
        // acceptance path safe rather than merely careful.
        assertThatThrownBy {
            organizationMemberRepository.saveAndFlush(
                OrganizationMember(organizationId, bob.id, OrganizationRole.MEMBER),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(organizationMemberRepository.findAll().count { it.userId == bob.id }).isEqualTo(1)
    }

    @Test
    fun `two administrators inviting the same address at once create one invitation`() {
        val admin = newUser("admin@example.com")
        addMember(organizationId, admin, OrganizationRole.ADMIN)

        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            pool.invokeAll(
                listOf(inviteTask(barrier, owner), inviteTask(barrier, admin)),
                30,
                TimeUnit.SECONDS,
            ).map { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }

        // The partial unique index on (organization_id, email) WHERE accepted_at IS NULL is
        // what decides; the service pre-check only makes the common case read nicely.
        assertThat(results.filter { it.isSuccess }).hasSize(1)
        assertThat(organizationInvitationRepository.count()).isEqualTo(1)
    }

    private fun acceptTask(barrier: CyclicBarrier, token: String): Callable<UUID> =
        Callable {
            barrier.await(20, TimeUnit.SECONDS)
            actingAs(bob) {
                invitationAcceptanceService.accept(AcceptInvitationRequest(token = token)).organizationId
            }
        }

    private fun inviteTask(barrier: CyclicBarrier, caller: TestUser): Callable<Int> =
        Callable {
            barrier.await(20, TimeUnit.SECONDS)
            val status = postJson(
                "/api/organizations/$organizationId/invitations",
                """{"email":"newcomer@example.com"}""",
                caller.accessToken,
            ).andReturn().response.status

            if (status != 201) throw IllegalStateException("invite returned $status")
            status
        }

    private fun <T> actingAs(user: TestUser, block: () -> T): T {
        val jwt = jwtDecoder.decode(user.accessToken)
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt, emptyList())

        return try {
            block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
