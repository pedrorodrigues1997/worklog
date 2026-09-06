package com.tictac.io.project

import com.tictac.io.organization.OrganizationNotFoundException
import com.tictac.io.organization.OrganizationRole
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
 * Project authorisation at the component that enforces it, rather than through controllers.
 *
 * The HTTP tests prove today's endpoints are wired to this gate; these prove the gate itself
 * is right, so a future endpoint that calls it inherits a checked guarantee. Nothing is
 * mocked - callers are established from real access tokens decoded by the application's own
 * [JwtDecoder].
 */
@DisplayName("ProjectAccess: the project-scoped authorization gate")
class ProjectAccessIntegrationTest : OrganizationApiTest() {

    @Autowired
    private lateinit var projectAccess: ProjectAccess

    @Autowired
    private lateinit var jwtDecoder: JwtDecoder

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var assigned: TestUser
    private lateinit var unassigned: TestUser
    private lateinit var outsider: TestUser
    private var organizationId = UUID.randomUUID()
    private var projectId = UUID.randomUUID()
    private var foreignOrganizationId = UUID.randomUUID()
    private var foreignProjectId = UUID.randomUUID()

    @BeforeEach
    fun createTwoTenants() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        assigned = newUser("assigned@example.com")
        unassigned = newUser("unassigned@example.com")
        outsider = newUser("outsider@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, assigned, OrganizationRole.MEMBER)
        addMember(organizationId, unassigned, OrganizationRole.MEMBER)

        projectId = createProject(organizationId, owner, "Website Redesign")
        assignToProject(organizationId, projectId, assigned, owner)

        foreignOrganizationId = createOrganization(outsider, "Other Company")
        foreignProjectId = createProject(foreignOrganizationId, outsider, "Other Project")
    }

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    // --- who can see a project ---------------------------------------------------------

    @Test
    fun `administrators resolve the project without being assigned to it`() {
        listOf(owner, admin).forEach { user ->
            val context = actingAs(user) { projectAccess.require(organizationId, projectId) }

            assertThat(context.projectId).isEqualTo(projectId)
            assertThat(context.organizationId).isEqualTo(organizationId)
            assertThat(context.userId).isEqualTo(user.id)
            assertThat(context.isAssigned).isFalse()
        }
    }

    @Test
    fun `an assigned member resolves the project and is marked as assigned`() {
        val context = actingAs(assigned) { projectAccess.require(organizationId, projectId) }

        assertThat(context.isAssigned).isTrue()
        assertThat(context.assignment!!.userId).isEqualTo(assigned.id)
        assertThat(context.organizationRole).isEqualTo(OrganizationRole.MEMBER)
    }

    @Test
    fun `an unassigned member is refused as if the project did not exist`() {
        assertThatThrownBy { actingAs(unassigned) { projectAccess.require(organizationId, projectId) } }
            .isInstanceOf(ProjectNotFoundException::class.java)
    }

    @Test
    fun `a non-member of the organization never reaches the project check at all`() {
        // Refused one layer earlier, by OrganizationAccess - which is what stops a stranger
        // learning whether the organization itself exists.
        assertThatThrownBy { actingAs(outsider) { projectAccess.require(organizationId, projectId) } }
            .isInstanceOf(OrganizationNotFoundException::class.java)
    }

    // --- the organization boundary --------------------------------------------------------

    @Test
    fun `a project id from another organization resolves to nothing`() {
        // The caller genuinely administers this organization; the project id is genuinely
        // real. The pair is what fails, which is the whole point of looking the project up
        // by both ids at once.
        assertThatThrownBy { actingAs(owner) { projectAccess.require(organizationId, foreignProjectId) } }
            .isInstanceOf(ProjectNotFoundException::class.java)
    }

    @Test
    fun `a foreign project is refused identically to one that does not exist`() {
        val foreign = catching { actingAs(owner) { projectAccess.require(organizationId, foreignProjectId) } }
        val unknown = catching { actingAs(owner) { projectAccess.require(organizationId, UUID.randomUUID()) } }

        assertThat(foreign).isInstanceOf(ProjectNotFoundException::class.java)
        assertThat(unknown).isInstanceOf(ProjectNotFoundException::class.java)
        assertThat(foreign!!.message).isEqualTo(unknown!!.message)
    }

    @Test
    fun `being assigned to a project does not survive naming the wrong organization`() {
        assertThatThrownBy {
            actingAs(assigned) { projectAccess.require(foreignOrganizationId, projectId) }
        }.isInstanceOf(OrganizationNotFoundException::class.java)
    }

    @Test
    fun `a soft-deleted organization takes its projects with it`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)

        listOf(owner, admin, assigned).forEach { user ->
            assertThatThrownBy { actingAs(user) { projectAccess.require(organizationId, projectId) } }
                .isInstanceOf(OrganizationNotFoundException::class.java)
        }
    }

    // --- the role gate ----------------------------------------------------------------------

    @Test
    fun `an assigned member is denied rather than hidden from when they lack the role`() {
        // Distinguishable on purpose: they can already see the project, so 403 tells them
        // nothing their own project list does not.
        assertThatThrownBy {
            actingAs(assigned) {
                projectAccess.require(organizationId, projectId, OrganizationRole.OWNER, OrganizationRole.ADMIN)
            }
        }.isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `an unassigned member gets not-found rather than forbidden even for a role-gated call`() {
        // Visibility is checked before the role, so the refusal cannot confirm the project
        // exists to someone who was never allowed to know.
        assertThatThrownBy {
            actingAs(unassigned) {
                projectAccess.require(organizationId, projectId, OrganizationRole.OWNER, OrganizationRole.ADMIN)
            }
        }.isInstanceOf(ProjectNotFoundException::class.java)
    }

    @Test
    fun `an allowed role passes the role gate`() {
        listOf(owner to OrganizationRole.OWNER, admin to OrganizationRole.ADMIN).forEach { (user, role) ->
            val context = actingAs(user) {
                projectAccess.require(organizationId, projectId, OrganizationRole.OWNER, OrganizationRole.ADMIN)
            }

            assertThat(context.organizationRole).isEqualTo(role)
        }
    }

    @Test
    fun `a still-valid token for a closed account resolves to nothing`() {
        userRepository.findById(assigned.id).orElseThrow()
            .also { it.deletedAt = Instant.now() }
            .let { userRepository.saveAndFlush(it) }

        // The signature is still good; the account check inside OrganizationAccess is what
        // closes the window, and the assignment row does not resurrect it.
        assertThatThrownBy { actingAs(assigned) { projectAccess.require(organizationId, projectId) } }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `an unauthenticated context resolves nothing`() {
        SecurityContextHolder.clearContext()

        assertThatThrownBy { projectAccess.require(organizationId, projectId) }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    // --- the queries underneath ---------------------------------------------------------------

    @Test
    fun `the assigned-projects query is scoped to one organization and one user`() {
        // Same person, assigned in two organizations: each list must contain only its own.
        addMember(foreignOrganizationId, assigned, OrganizationRole.MEMBER)
        assignToProject(foreignOrganizationId, foreignProjectId, assigned, outsider)

        assertThat(projectRepository.findAssignedInOrganization(organizationId, assigned.id).map { it.id })
            .containsExactly(projectId)
        assertThat(projectRepository.findAssignedInOrganization(foreignOrganizationId, assigned.id).map { it.id })
            .containsExactly(foreignProjectId)
        assertThat(projectRepository.findAssignedInOrganization(organizationId, unassigned.id)).isEmpty()
    }

    @Test
    fun `the project lookup requires both ids to match`() {
        assertThat(projectRepository.findByIdAndOrganizationId(projectId, organizationId)).isNotNull
        assertThat(projectRepository.findByIdAndOrganizationId(projectId, foreignOrganizationId)).isNull()
        assertThat(projectRepository.findByIdAndOrganizationId(foreignProjectId, organizationId)).isNull()
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

    private fun catching(block: () -> Any?): Throwable? =
        try {
            block()
            null
        } catch (ex: Throwable) {
            ex
        }
}
