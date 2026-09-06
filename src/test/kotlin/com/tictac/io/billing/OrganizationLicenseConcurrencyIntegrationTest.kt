package com.tictac.io.billing

import com.jayway.jsonpath.JsonPath
import com.tictac.io.organization.OrganizationRole
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Licence allocation racing itself.
 *
 * Reading "is a licence free?" and then acting on the answer is only safe while nothing else
 * can change it in between. Two administrators inviting at the same instant would otherwise
 * both be told the same single vacant licence is theirs, and the organization would end up
 * with more people in it than it holds licences for - silently, and permanently.
 *
 * The serialisation point is `SELECT ... FOR UPDATE` on the *organization* row: it always
 * exists (the subscription may not), it holds across application instances (an in-memory lock
 * would not), and it is the row `license_count` itself lives on.
 *
 * Every test here asserts the same invariant: **`membersOccupying <= licenseCount`**. Nobody
 * is ever in a licence the organization does not hold.
 */
@DisplayName("Licence allocation under contention")
class OrganizationLicenseConcurrencyIntegrationTest : OrganizationApiTest() {

    @MockitoBean
    private lateinit var stripeGateway: StripeGateway

    private lateinit var owner: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        organizationId = createOrganization(owner, "Acme")

        whenever(stripeGateway.createCustomer(any(), any())).thenReturn("cus_test")
    }

    private fun assertNobodyIsUnlicensed() {
        val members = memberCountOf(organizationId)
        val licenses = licenseCountOf(organizationId).toLong()

        assertThat(members)
            .describedAs("%d members occupying %d licences", members, licenses)
            .isLessThanOrEqualTo(licenses)
    }

    /** An invitation ready to be accepted, and the account that will accept it. */
    private fun pendingJoin(email: String): Pair<TestUser, String> {
        val user = newUser(email)
        return user to inviteToOrganization(organizationId, email, owner)
    }

    private fun acceptTask(barrier: CyclicBarrier, user: TestUser, token: String): Callable<Int> =
        Callable {
            barrier.await(20, TimeUnit.SECONDS)
            postJson("/api/invitations/accept", """{"token":"$token"}""", user.accessToken)
                .andReturn().response.status
        }

    private fun inviteTask(barrier: CyclicBarrier, email: String, caller: TestUser): Callable<Int> =
        Callable {
            barrier.await(20, TimeUnit.SECONDS)
            postJson(
                "/api/organizations/$organizationId/invitations",
                """{"email":"$email"}""",
                caller.accessToken,
            ).andReturn().response.status
        }

    private fun removeTask(barrier: CyclicBarrier, target: TestUser): Callable<Int> =
        Callable {
            barrier.await(20, TimeUnit.SECONDS)
            deleteRequest("/api/organizations/$organizationId/members/${target.id}", owner.accessToken)
                .andReturn().response.status
        }

    private fun <T> runTogether(tasks: List<Callable<T>>): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(tasks.size)

        return try {
            pool.invokeAll(tasks, 60, TimeUnit.SECONDS).map { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }
    }

    // --- issuing invitations concurrently ------------------------------------------------------

    @Test
    fun `two administrators inviting at once cannot both be given the same vacant licence`() {
        // Two licences, one member: exactly one is vacant.
        subscribeOrganization(organizationId, licenses = 2)
        val admin = newUser("admin@example.com").also { addMember(organizationId, it, OrganizationRole.ADMIN) }
        // ...and the admin joining took it, so the next invitation must acquire one.
        setLicenseCount(organizationId, 3)

        val barrier = CyclicBarrier(2)
        val statuses = runTogether(
            listOf(
                inviteTask(barrier, "bob@example.com", owner),
                inviteTask(barrier, "carol@example.com", admin),
            ),
        ).mapNotNull { it.getOrNull() }

        assertThat(statuses).allMatch { it == 201 }

        // Three licences, one vacant, two invitations outstanding. If they had raced, both
        // would have taken the same vacant licence and the count would still be three.
        assertThat(licenseCountOf(organizationId)).isEqualTo(4)
        assertThat(organizationInvitationRepository.count()).isEqualTo(2)
    }

    @Test
    fun `five invitations at once acquire exactly the licences they need`() {
        // Five licences, one member: four vacant, so five invitations need exactly one more.
        subscribeOrganization(organizationId, licenses = 5)

        val emails = (1..5).map { "joiner$it@example.com" }
        val barrier = CyclicBarrier(emails.size)
        val statuses = runTogether(emails.map { inviteTask(barrier, it, owner) }).mapNotNull { it.getOrNull() }

        assertThat(statuses).allMatch { it == 201 }
        assertThat(organizationInvitationRepository.count()).isEqualTo(5)

        // Never six, never seven: each invitation either took a vacant licence or bought one.
        assertThat(licenseCountOf(organizationId)).isEqualTo(6)
    }

    // --- accepting concurrently -------------------------------------------------------------------

    @Test
    fun `two simultaneous acceptances of two invitations both fit, because both were paid for`() {
        subscribeOrganization(organizationId, licenses = 3)
        val joins = listOf(pendingJoin("bob@example.com"), pendingJoin("carol@example.com"))

        val barrier = CyclicBarrier(joins.size)
        val statuses = runTogether(joins.map { (user, token) -> acceptTask(barrier, user, token) })
            .mapNotNull { it.getOrNull() }

        assertThat(statuses).allMatch { it == 200 }
        assertThat(memberCountOf(organizationId)).isEqualTo(3)
        assertNobodyIsUnlicensed()
    }

    @Test
    fun `a licence pulled out from under two acceptances lets exactly one through`() {
        subscribeOrganization(organizationId, licenses = 3)
        val joins = listOf(pendingJoin("bob@example.com"), pendingJoin("carol@example.com"))

        // Both invitations exist and both were paid for - then an administrator gives a licence
        // back, leaving room for only one of them.
        putJson("/api/organizations/$organizationId/licenses", """{"licenses":2}""", owner.accessToken)

        val barrier = CyclicBarrier(joins.size)
        val statuses = runTogether(joins.map { (user, token) -> acceptTask(barrier, user, token) })
            .mapNotNull { it.getOrNull() }

        assertThat(statuses.count { it == 200 }).isEqualTo(1)
        assertThat(statuses.count { it == 409 }).isEqualTo(1)
        assertThat(memberCountOf(organizationId)).isEqualTo(2)
        assertNobodyIsUnlicensed()

        // The loser's invitation survives, so it works again once a licence is free.
        assertThat(organizationInvitationRepository.findAll().count { it.acceptedAt == null }).isEqualTo(1)
    }

    @Test
    fun `many acceptances racing for one remaining licence produce one member`() {
        subscribeOrganization(organizationId, licenses = 5)
        val joins = (1..4).map { pendingJoin("joiner$it@example.com") }

        // Four invitations, four licences bought for them - then all but one licence is
        // given back, and they all arrive together.
        putJson("/api/organizations/$organizationId/licenses", """{"licenses":2}""", owner.accessToken)

        val barrier = CyclicBarrier(joins.size)
        val statuses = runTogether(joins.map { (user, token) -> acceptTask(barrier, user, token) })
            .mapNotNull { it.getOrNull() }

        assertThat(statuses.count { it == 200 }).isEqualTo(1)
        assertThat(memberCountOf(organizationId)).isEqualTo(2)
        assertNobodyIsUnlicensed()
    }

    // --- removals and reductions racing ----------------------------------------------------------------

    @Test
    fun `simultaneous removals vacate licences and never touch the count`() {
        subscribeOrganization(organizationId, licenses = 5)
        val members = (1..4).map { index ->
            newUser("member$index@example.com").also { addMember(organizationId, it, OrganizationRole.MEMBER) }
        }

        val barrier = CyclicBarrier(members.size)
        val statuses = runTogether(members.map { removeTask(barrier, it) }).mapNotNull { it.getOrNull() }

        assertThat(statuses).allMatch { it == 204 }
        assertThat(memberCountOf(organizationId)).isEqualTo(1)

        // Five licences, four now vacant. However the removals interleaved, none of them
        // reduced anything.
        assertThat(licenseCountOf(organizationId)).isEqualTo(5)
        assertNobodyIsUnlicensed()
    }

    @Test
    fun `a reduction racing an acceptance cannot strand a member outside a licence`() {
        subscribeOrganization(organizationId, licenses = 3)
        val (joiner, token) = pendingJoin("joiner@example.com")
        newUser("staying@example.com").also { addMember(organizationId, it, OrganizationRole.MEMBER) }

        val barrier = CyclicBarrier(2)
        val reduce = Callable {
            barrier.await(20, TimeUnit.SECONDS)
            putJson("/api/organizations/$organizationId/licenses", """{"licenses":2}""", owner.accessToken)
                .andReturn().response.status
        }

        runTogether(listOf(acceptTask(barrier, joiner, token), reduce))

        // Both serialise on the organization row. If the acceptance committed first there are
        // three members and the reduction was refused; otherwise there are two. Either way
        // nobody ends up outside a licence.
        assertThat(memberCountOf(organizationId)).isBetween(2L, 3L)
        assertNobodyIsUnlicensed()
    }

    @Test
    fun `an invitation and a removal at the same time stay consistent`() {
        subscribeOrganization(organizationId, licenses = 2)
        val leaving = newUser("leaving@example.com")
            .also { addMember(organizationId, it, OrganizationRole.MEMBER) }

        val barrier = CyclicBarrier(2)
        val results = runTogether(
            listOf(
                inviteTask(barrier, "joiner@example.com", owner),
                removeTask(barrier, leaving),
            ),
        ).mapNotNull { it.getOrNull() }

        assertThat(results).contains(204)

        // The invitation either found the licence the removal freed, or bought one. It never
        // both found one and bought one.
        val licenses = licenseCountOf(organizationId)
        assertThat(licenses).isBetween(2, 3)
        assertNobodyIsUnlicensed()

        val token = organizationInvitationRepository.findAll().single().let { it }
        assertThat(token.acceptedAt).isNull()
    }

    @Test
    fun `the licence count never drifts from what Stripe was told`() {
        subscribeOrganization(organizationId, licenses = 2)

        val emails = (1..3).map { "joiner$it@example.com" }
        val barrier = CyclicBarrier(emails.size)
        runTogether(emails.map { inviteTask(barrier, it, owner) })

        // Whatever order they went in, the local count and the Stripe quantity agree: the
        // quantity is always one fewer, because the first licence is included free.
        val licenses = licenseCountOf(organizationId).toLong()
        assertThat(billedLicensesOf(organizationId)!!.toLong())
            .isEqualTo(OrganizationLicenses.paidLicensesFor(licenses))
    }

    @Test
    fun `concurrent invitations to the same address still create one invitation`() {
        subscribeOrganization(organizationId, licenses = 5)
        val admin = newUser("admin@example.com").also { addMember(organizationId, it, OrganizationRole.ADMIN) }

        val barrier = CyclicBarrier(2)
        val statuses = runTogether(
            listOf(
                inviteTask(barrier, "bob@example.com", owner),
                inviteTask(barrier, "bob@example.com", admin),
            ),
        ).mapNotNull { it.getOrNull() }

        // The partial unique index decides, and the loser's licence acquisition rolls back
        // with it - so a duplicate invitation cannot leave a paid-for licence behind.
        assertThat(statuses.count { it == 201 }).isEqualTo(1)
        assertThat(organizationInvitationRepository.count()).isEqualTo(1)
        assertThat(licenseCountOf(organizationId)).isEqualTo(5)
    }

    @Suppress("unused")
    private fun tokenOf(body: String): String = JsonPath.read(body, "$.token")
}
