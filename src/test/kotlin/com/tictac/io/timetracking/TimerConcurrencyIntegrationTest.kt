package com.tictac.io.timetracking

import com.tictac.io.organization.OrganizationRole
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Competing timer starts.
 *
 * "Check whether a timer is running, then insert" is not enough on its own: two transactions
 * can both pass the check before either commits. The application does that check anyway, for
 * a clean error message, but the partial unique index in V9 is the guarantee - and this is
 * where that claim gets tested rather than asserted.
 *
 * The service is called directly rather than through MockMvc: each thread needs its own
 * transaction and its own security context, and `SecurityContextHolder` is thread-local.
 * Nothing is mocked - callers come from real access tokens decoded by the application's own
 * [JwtDecoder].
 */
@DisplayName("Timer starts under contention")
class TimerConcurrencyIntegrationTest : OrganizationApiTest() {

    @Autowired
    private lateinit var timerService: TimerService

    @Autowired
    private lateinit var jwtDecoder: JwtDecoder

    private lateinit var bob: TestUser
    private lateinit var owner: TestUser
    private var organizationId = UUID.randomUUID()
    private var website = UUID.randomUUID()
    private var mobile = UUID.randomUUID()

    @BeforeEach
    fun createOrganizationWithProjects() {
        owner = newUser("owner@example.com")
        bob = newUser("bob@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, bob, OrganizationRole.MEMBER)

        website = createProject(organizationId, owner, "Website Redesign")
        mobile = createProject(organizationId, owner, "Mobile App")
        assignToProject(organizationId, website, bob, owner)
        assignToProject(organizationId, mobile, bob, owner)
    }

    @Test
    fun `two simultaneous starts cannot produce two running timers`() {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)

        val results = try {
            pool.invokeAll(
                listOf(startTask(barrier, website), startTask(barrier, mobile)),
                30,
                TimeUnit.SECONDS,
            ).map { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }

        // Exactly one wins. The loser fails either on the pre-check or on the unique index,
        // depending only on timing - both are correct refusals, and both roll back.
        assertThat(results.filter { it.isSuccess })
            .describedAs("exactly one start should succeed")
            .hasSize(1)

        assertThat(runningTimers()).hasSize(1)
        assertThat(timeEntryRepository.count())
            .describedAs("the losing transaction must leave no row behind")
            .isEqualTo(1)
    }

    @Test
    fun `many simultaneous starts still produce exactly one running timer`() {
        val attempts = 8
        val barrier = CyclicBarrier(attempts)
        val pool = Executors.newFixedThreadPool(attempts)

        val results = try {
            pool.invokeAll(
                (0 until attempts).map { startTask(barrier, if (it % 2 == 0) website else mobile) },
                30,
                TimeUnit.SECONDS,
            ).map { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }

        assertThat(results.filter { it.isSuccess }).hasSize(1)
        assertThat(runningTimers()).hasSize(1)
        assertThat(timeEntryRepository.count()).isEqualTo(1)
    }

    @Test
    fun `concurrent starts in different organizations both succeed`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")
        addMember(otherOrganization, bob, OrganizationRole.MEMBER)
        val otherProject = createProject(otherOrganization, otherOwner, "Other Project")
        assignToProject(otherOrganization, otherProject, bob, otherOwner)

        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)

        val results = try {
            pool.invokeAll(
                listOf(
                    startTask(barrier, website, organizationId),
                    startTask(barrier, otherProject, otherOrganization),
                ),
                30,
                TimeUnit.SECONDS,
            ).map { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }

        // The constraint is per organization, so these do not contend at all.
        assertThat(results.filter { it.isSuccess }).hasSize(2)
        assertThat(timeEntryRepository.count()).isEqualTo(2)
    }

    @Test
    fun `the database refuses a second running timer even with the service out of the way`() {
        startTimer(organizationId, website, bob)

        // The last line of defence, asserted without going through any application code:
        // whatever a future code path does, the partial unique index from V9 stands.
        assertThatThrownBy {
            timeEntryRepository.saveAndFlush(
                TimeEntry(
                    organizationId = organizationId,
                    projectId = mobile,
                    userId = bob.id,
                    startedAt = Instant.now(),
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(runningTimers()).hasSize(1)
    }

    @Test
    fun `a stopped timer does not occupy the slot`() {
        val first = startTimer(organizationId, website, bob)
        timeEntryRepository.findById(first).orElseThrow()
            .also { it.stopAt(Instant.now()) }
            .let { timeEntryRepository.saveAndFlush(it) }

        // The index only covers rows with a null ended_at, so this inserts cleanly.
        timeEntryRepository.saveAndFlush(
            TimeEntry(
                organizationId = organizationId,
                projectId = mobile,
                userId = bob.id,
                startedAt = Instant.now(),
            ),
        )

        assertThat(runningTimers()).hasSize(1)
        assertThat(timeEntryRepository.count()).isEqualTo(2)
    }

    private fun runningTimers() =
        timeEntryRepository.findAll().filter { it.endedAt == null && it.deletedAt == null }

    private fun startTask(
        barrier: CyclicBarrier,
        projectId: UUID,
        organization: UUID = organizationId,
    ): Callable<UUID> =
        Callable {
            barrier.await(20, TimeUnit.SECONDS)
            actingAs(bob) {
                timerService.start(organization, StartTimerRequest(projectId = projectId, title = "Work")).id
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
}
