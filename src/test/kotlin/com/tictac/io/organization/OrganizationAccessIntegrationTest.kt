package com.tictac.io.organization

import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import java.util.UUID

/**
 * Tenant isolation at the component that enforces it, rather than through the controllers.
 *
 * The HTTP tests prove the current endpoints are wired to this gate; these prove the gate
 * itself is right, so a future endpoint that calls it inherits a checked guarantee instead
 * of an assumption. Between them they cover both halves - that the check exists, and that
 * it is correct.
 *
 * No security is mocked. The caller is established from a real access token issued by the
 * real login endpoint and decoded by the application's own [JwtDecoder]; only the servlet
 * filter that would normally place it in the context is missing, because there is no
 * request here.
 */
@DisplayName("OrganizationAccess: the tenant-scoped authorization gate")
class OrganizationAccessIntegrationTest : OrganizationApiTest() {

    @Autowired
    private lateinit var organizationAccess: OrganizationAccess

    @Autowired
    private lateinit var jwtDecoder: JwtDecoder

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var member: TestUser
    private lateinit var outsider: TestUser
    private var organizationId = UUID.randomUUID()
    private var foreignOrganizationId = UUID.randomUUID()

    @BeforeEach
    fun createTwoTenants() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        member = newUser("member@example.com")
        outsider = newUser("outsider@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, member, OrganizationRole.MEMBER)

        foreignOrganizationId = createOrganization(outsider, "Other Company")
    }

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a member resolves to their own role in that organization`() {
        listOf(owner to OrganizationRole.OWNER, admin to OrganizationRole.ADMIN, member to OrganizationRole.MEMBER)
            .forEach { (user, expectedRole) ->
                val context = actingAs(user) { organizationAccess.require(organizationId) }

                assertThat(context.role).isEqualTo(expectedRole)
                assertThat(context.organizationId).isEqualTo(organizationId)
                assertThat(context.userId).isEqualTo(user.id)
                assertThat(context.user.email).isEqualTo(user.email)
            }
    }

    @Test
    fun `the caller resolved is the token's subject, never anything else`() {
        val context = actingAs(member) { organizationAccess.require(organizationId) }

        // There is no parameter through which a caller could nominate someone else - the
        // only inputs are the organization id and the security context.
        assertThat(context.userId).isEqualTo(member.id)
        assertThat(context.userId).isNotEqualTo(owner.id)
    }

    @Test
    fun `a non-member is refused, whatever role is asked for`() {
        assertThatThrownBy { actingAs(outsider) { organizationAccess.require(organizationId) } }
            .isInstanceOf(OrganizationNotFoundException::class.java)

        assertThatThrownBy {
            actingAs(outsider) { organizationAccess.require(organizationId, OrganizationRole.MEMBER) }
        }.isInstanceOf(OrganizationNotFoundException::class.java)
    }

    @Test
    fun `an unknown organization id is refused the same way a foreign one is`() {
        val foreign = catching { actingAs(outsider) { organizationAccess.require(organizationId) } }
        val unknown = catching { actingAs(outsider) { organizationAccess.require(UUID.randomUUID()) } }

        assertThat(foreign).isInstanceOf(OrganizationNotFoundException::class.java)
        assertThat(unknown).isInstanceOf(OrganizationNotFoundException::class.java)
        assertThat(foreign!!.message).isEqualTo(unknown!!.message)
    }

    @Test
    fun `a soft-deleted organization is refused even to its owner`() {
        organizationRepository.findById(organizationId).orElseThrow()
            .also { it.deletedAt = Instant.now() }
            .let { organizationRepository.saveAndFlush(it) }

        assertThatThrownBy { actingAs(owner) { organizationAccess.require(organizationId) } }
            .isInstanceOf(OrganizationNotFoundException::class.java)
    }

    @Test
    fun `a member holding the wrong role is denied rather than hidden from`() {
        // Distinguishable on purpose: the caller is a member and already knows the
        // organization exists, so 403 leaks nothing that their own listing does not.
        assertThatThrownBy {
            actingAs(member) { organizationAccess.require(organizationId, OrganizationRole.OWNER) }
        }.isInstanceOf(AccessDeniedException::class.java)

        assertThatThrownBy {
            actingAs(admin) { organizationAccess.require(organizationId, OrganizationRole.OWNER) }
        }.isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `an allowed role passes the role gate`() {
        val asOwner = actingAs(owner) {
            organizationAccess.require(organizationId, OrganizationRole.OWNER, OrganizationRole.ADMIN)
        }
        val asAdmin = actingAs(admin) {
            organizationAccess.require(organizationId, OrganizationRole.OWNER, OrganizationRole.ADMIN)
        }

        assertThat(asOwner.role).isEqualTo(OrganizationRole.OWNER)
        assertThat(asAdmin.role).isEqualTo(OrganizationRole.ADMIN)
    }

    @Test
    fun `an unauthenticated context cannot resolve any organization`() {
        SecurityContextHolder.clearContext()

        assertThatThrownBy { organizationAccess.require(organizationId) }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `a still-valid token for a closed account resolves to nothing`() {
        userRepository.findById(member.id).orElseThrow()
            .also { it.deletedAt = Instant.now() }
            .let { userRepository.saveAndFlush(it) }

        // The signature is still good - access tokens are not revocable inside their
        // lifetime - so the account check in ActiveUser is what closes this window.
        assertThatThrownBy { actingAs(member) { organizationAccess.require(organizationId) } }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    // --- the queries underneath ------------------------------------------------------

    @Test
    fun `the listing query returns only the given user's organizations`() {
        assertThat(organizationMemberRepository.findOrganizationsForUser(owner.id).map { it.id })
            .containsExactly(organizationId)
        assertThat(organizationMemberRepository.findOrganizationsForUser(outsider.id).map { it.id })
            .containsExactly(foreignOrganizationId)
        assertThat(organizationMemberRepository.findOrganizationsForUser(UUID.randomUUID()))
            .isEmpty()
    }

    @Test
    fun `the member query returns only the given organization's members`() {
        assertThat(organizationMemberRepository.findMembersOfOrganization(organizationId).map { it.userId })
            .containsExactlyInAnyOrder(owner.id, admin.id, member.id)
        assertThat(organizationMemberRepository.findMembersOfOrganization(foreignOrganizationId).map { it.userId })
            .containsExactly(outsider.id)
        assertThat(organizationMemberRepository.findMembersOfOrganization(UUID.randomUUID()))
            .isEmpty()
    }

    @Test
    fun `the member query leaves out accounts that have been closed`() {
        userRepository.findById(member.id).orElseThrow()
            .also { it.deletedAt = Instant.now() }
            .let { userRepository.saveAndFlush(it) }

        assertThat(organizationMemberRepository.findMembersOfOrganization(organizationId).map { it.userId })
            .containsExactlyInAnyOrder(owner.id, admin.id)
    }

    /**
     * Runs [block] with [user] as the authenticated caller, using a token they really
     * signed in with and this application's own decoder.
     */
    private fun <T> actingAs(user: TestUser, block: () -> T): T {
        val jwt = jwtDecoder.decode(user.accessToken)
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt, emptyList())

        return try {
            block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun catching(block: () -> Any?): Throwable? =
        try {
            block()
            null
        } catch (ex: Throwable) {
            ex
        }
}
