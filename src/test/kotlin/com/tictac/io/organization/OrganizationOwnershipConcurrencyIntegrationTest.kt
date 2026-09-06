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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Competing ownership transfers.
 *
 * Two requests from the same owner, aimed at different targets, launched together. The
 * outcome that must never happen is two owners, and the one that must never happen quietly
 * is zero. Both are checked against the database rather than inferred from the responses.
 *
 * The service is called directly rather than through MockMvc: each thread needs its own
 * transaction and its own security context, and `SecurityContextHolder` is thread-local, so
 * this is the shape that actually produces contention. Nothing is mocked - the callers are
 * established from real access tokens decoded by the application's own [JwtDecoder].
 */
@DisplayName("Ownership transfer under contention")
class OrganizationOwnershipConcurrencyIntegrationTest : OrganizationApiTest() {

    @Autowired
    private lateinit var organizationOwnershipService: OrganizationOwnershipService

    @Autowired
    private lateinit var jwtDecoder: JwtDecoder

    private lateinit var pedro: TestUser
    private lateinit var alice: TestUser
    private lateinit var bob: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        pedro = newUser("pedro@example.com")
        alice = newUser("alice@example.com")
        bob = newUser("bob@example.com")

        organizationId = createOrganization(pedro, "Acme")
        addMember(organizationId, alice, OrganizationRole.MEMBER)
        addMember(organizationId, bob, OrganizationRole.MEMBER)
    }

    @Test
    fun `two simultaneous transfers from the same owner cannot produce two owners`() {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)

        val results = try {
            pool.invokeAll(
                listOf(
                    transferTask(barrier, target = alice),
                    transferTask(barrier, target = bob),
                ),
                30,
                TimeUnit.SECONDS,
            ).map { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }

        // Exactly one request wins. The loser fails either because it could not find an
        // owner row to demote (it lost the race for the lock) or because its own
        // authorisation check ran after the winner had already committed - both are
        // correct refusals, and which one happens depends only on timing.
        val winners = results.filter { it.isSuccess }
        assertThat(winners).describedAs("exactly one transfer should succeed").hasSize(1)

        assertThat(ownerCount()).isEqualTo(1)

        val newOwner = winners.single().getOrThrow()
        assertThat(roleOf(organizationId, pedro)).isEqualTo(OrganizationRole.ADMIN)
        assertThat(roleOfUser(newOwner)).isEqualTo(OrganizationRole.OWNER)

        // The loser's target is untouched - a rolled-back transfer leaves no trace.
        val loserTarget = listOf(alice, bob).single { it.id != newOwner }
        assertThat(roleOf(organizationId, loserTarget)).isEqualTo(OrganizationRole.MEMBER)
    }

    @Test
    fun `the database refuses a second owner even with the service out of the way`() {
        // The last line of defence, asserted without going through any application code:
        // whatever a future code path does, the partial unique index from V6 stands.
        assertThatThrownBy {
            organizationMemberRepository.saveAndFlush(
                OrganizationMember(organizationId, newUser("newcomer@example.com").id, OrganizationRole.OWNER),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(ownerCount()).isEqualTo(1)
        assertThat(roleOf(organizationId, pedro)).isEqualTo(OrganizationRole.OWNER)
    }

    @Test
    fun `an organization is never left without an owner when a transfer fails`() {
        // A transfer that fails late - the target's account closes first, so the failure
        // happens after the demote would otherwise have been written.
        deleteRequest("/api/users/me", alice.accessToken).andExpect(status().isNoContent)

        assertThatThrownBy {
            actingAs(pedro) {
                organizationOwnershipService.transferOwnership(
                    organizationId,
                    TransferOwnershipRequest(alice.id),
                )
            }
        }.isInstanceOf(OwnershipTransferConflictException::class.java)

        assertThat(ownerCount()).isEqualTo(1)
        assertThat(roleOf(organizationId, pedro)).isEqualTo(OrganizationRole.OWNER)
    }

    /** Returns the new owner's user id, or throws whatever the service threw. */
    private fun transferTask(barrier: CyclicBarrier, target: TestUser): Callable<UUID> =
        Callable {
            barrier.await(20, TimeUnit.SECONDS)
            actingAs(pedro) {
                organizationOwnershipService
                    .transferOwnership(organizationId, TransferOwnershipRequest(target.id))
                    .newOwner
                    .userId
            }
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

    private fun ownerCount() =
        organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER)

    private fun roleOfUser(userId: UUID) =
        organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)?.role
}
