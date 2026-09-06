package com.tictac.io.organization

import com.jayway.jsonpath.JsonPath
import com.tictac.io.common.security.SecureToken
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Accepting an invitation, by both routes.
 *
 * These are the tests that prove onboarding actually works end to end: every one goes
 * through HTTP, the real security filter chain, the controller, the transactional service
 * and a real PostgreSQL container. Nothing is stubbed, and in particular the "who is
 * accepting" question is answered by a real signed access token throughout.
 */
@DisplayName("Organization invitations: acceptance")
class InvitationAcceptanceIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        organizationId = createOrganization(owner, "Acme")

        // One member so far, so the person about to join is the second - the first that
        // costs anything. Seeded with exactly that one seat, which is what makes acceptance
        // possible at all now and needs no Stripe call to apply.
        subscribeOrganization(organizationId, licenses = 2)
    }

    private fun acceptAsExistingUser(token: String, user: TestUser) =
        postJson("/api/invitations/accept", """{"token":"$token"}""", user.accessToken)

    private fun registerWith(
        token: String,
        email: String,
        firstName: String = "Bob",
        password: String = DEFAULT_PASSWORD,
    ) = postJson(
        "/api/invitations/register",
        """{"token":"$token","firstName":"$firstName","lastName":"Smith",""" +
            """"email":"$email","password":"$password"}""",
    )

    private fun invitation() = organizationInvitationRepository.findAll().single()

    // --- an existing user accepts -----------------------------------------------------------

    @Test
    fun `an existing user accepts their invitation and becomes a MEMBER`() {
        val bob = newUser("bob@example.com")
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        acceptAsExistingUser(token, bob)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
            .andExpect(jsonPath("$.organizationName").value("Acme"))
            .andExpect(jsonPath("$.userId").value(bob.id.toString()))
            .andExpect(jsonPath("$.role").value("MEMBER"))
            .andExpect(jsonPath("$.acceptedAt").isNotEmpty)

        assertThat(roleOf(organizationId, bob)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(invitation().acceptedAt).isNotNull()

        // ...and the organization is genuinely usable, which is the whole point.
        getRequest("/api/organizations/$organizationId", bob.accessToken).andExpect(status().isOk)
        getRequest("/api/organizations", bob.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
    }

    @Test
    fun `no second account is created for an existing user`() {
        val bob = newUser("bob@example.com")
        val usersBefore = userRepository.count()
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        acceptAsExistingUser(token, bob).andExpect(status().isOk)

        assertThat(userRepository.count()).isEqualTo(usersBefore)
        assertThat(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, bob.id)!!.userId)
            .isEqualTo(bob.id)
    }

    @Test
    fun `someone else cannot accept an invitation addressed to another person`() {
        newUser("alice@example.com")
        val bob = newUser("bob@example.com")
        val aliceToken = inviteToOrganization(organizationId, "alice@example.com", owner)

        // Bob has the link - forwarded, leaked, guessed at, it does not matter. Possession
        // of the token proves he holds the *link*, not that he is the person it was for.
        acceptAsExistingUser(aliceToken, bob).andExpect(status().isForbidden)

        assertThat(roleOf(organizationId, bob)).isNull()
        assertThat(invitation().acceptedAt).isNull()
    }

    @Test
    fun `the invited email is compared against the token's identity, not the request body`() {
        newUser("alice@example.com")
        val bob = newUser("bob@example.com")
        val aliceToken = inviteToOrganization(organizationId, "alice@example.com", owner)

        // Bob claiming to be Alice in the body changes nothing: the endpoint has no email
        // field, and the comparison uses the account behind the access token.
        postJson(
            "/api/invitations/accept",
            """{"token":"$aliceToken","email":"alice@example.com","userId":"${bob.id}"}""",
            bob.accessToken,
        ).andExpect(status().isForbidden)

        assertThat(roleOf(organizationId, bob)).isNull()
    }

    @Test
    fun `accepting requires authentication`() {
        newUser("bob@example.com")
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        postJson("/api/invitations/accept", """{"token":"$token"}""")
            .andExpect(status().isUnauthorized)

        assertThat(invitation().acceptedAt).isNull()
        assertThat(organizationMemberRepository.count()).isEqualTo(1)
    }

    @Test
    fun `an existing member accepting again is a conflict, not a second membership`() {
        val bob = newUser("bob@example.com")
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        // Joined by some other route between the invitation and its acceptance.
        addMember(organizationId, bob, OrganizationRole.MEMBER)

        acceptAsExistingUser(token, bob)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Already a member"))

        // The (organization_id, user_id) index is the guarantee; this is it answering.
        assertThat(organizationMemberRepository.findAll().count { it.userId == bob.id }).isEqualTo(1)
        assertThat(invitation().acceptedAt).isNull()
    }

    // --- a new user registers and accepts -----------------------------------------------------

    @Test
    fun `someone with no account registers through the invitation and becomes a MEMBER`() {
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)
        assertThat(userRepository.findByEmail("bob@example.com")).isNull()

        registerWith(token, "bob@example.com")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
            .andExpect(jsonPath("$.role").value("MEMBER"))

        val bob = userRepository.findByEmail("bob@example.com")!!
        assertThat(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, bob.id!!)!!.role)
            .isEqualTo(OrganizationRole.MEMBER)
        assertThat(invitation().acceptedAt).isNotNull()
    }

    @Test
    fun `the account created that way is a normal account`() {
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)
        registerWith(token, "bob@example.com").andExpect(status().isCreated)

        // Registration went through the ordinary service, so the password is hashed the
        // ordinary way and the account signs in through the ordinary endpoint.
        val tokens = login("bob@example.com")
        getRequest("/api/users/me", tokens.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value("bob@example.com"))

        val bob = userRepository.findByEmail("bob@example.com")!!
        assertThat(bob.passwordHash).isNotNull().doesNotContain(DEFAULT_PASSWORD)
        assertThat(bob.passwordHash).startsWith("{argon2}")
    }

    @Test
    fun `an invitation cannot be used to register a different address`() {
        val token = inviteToOrganization(organizationId, "alice@example.com", owner)

        // The attack this closes: holding Alice's invitation must not be a licence to create
        // bob@example.com inside Alice's company.
        registerWith(token, "bob@example.com")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.title").value("Invitation email mismatch"))

        assertThat(userRepository.findByEmail("bob@example.com")).isNull()
        assertThat(organizationMemberRepository.count()).isEqualTo(1)
        assertThat(invitation().acceptedAt).isNull()
    }

    @Test
    fun `the registration address is normalised before it is compared`() {
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        registerWith(token, "BOB@Example.COM").andExpect(status().isCreated)

        assertThat(userRepository.findByEmail("bob@example.com")).isNotNull
    }

    @Test
    fun `registering through an invitation still requires real credentials`() {
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        // The token proves possession of the invitation; it does not stand in for creating
        // an account, so every field registration has always demanded is still demanded.
        listOf(
            """{"token":"$token"}""",
            """{"token":"$token","email":"bob@example.com"}""",
            """{"token":"$token","firstName":"Bob","lastName":"Smith","email":"bob@example.com"}""",
            """{"token":"$token","firstName":"Bob","lastName":"Smith","email":"bob@example.com","password":"short"}""",
        ).forEach { postJson("/api/invitations/register", it).andExpect(status().isBadRequest) }

        assertThat(userRepository.findByEmail("bob@example.com")).isNull()
        assertThat(invitation().acceptedAt).isNull()
    }

    @Test
    fun `registering at an address that already has an account is a conflict`() {
        newUser("bob@example.com")
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        registerWith(token, "bob@example.com")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Email already registered"))

        // Nothing half-applied: no membership, and the invitation is still usable by the
        // right route once Bob signs in.
        assertThat(organizationMemberRepository.count()).isEqualTo(1)
        assertThat(invitation().acceptedAt).isNull()
    }

    @Test
    fun `a failed registration leaves no account, no membership and an unconsumed invitation`() {
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)
        val usersBefore = userRepository.count()

        registerWith(token, "bob@example.com", password = "short").andExpect(status().isBadRequest)

        assertThat(userRepository.count()).isEqualTo(usersBefore)
        assertThat(organizationMemberRepository.count()).isEqualTo(1)
        assertThat(invitation().acceptedAt).isNull()

        // ...and the invitation still works afterwards, which is what "unconsumed" has to mean.
        registerWith(token, "bob@example.com").andExpect(status().isCreated)
    }

    // --- the token -------------------------------------------------------------------------------

    @Test
    fun `an unknown token is refused`() {
        newUser("bob@example.com")
        inviteToOrganization(organizationId, "bob@example.com", owner)
        val bob = userRepository.findByEmail("bob@example.com")!!

        listOf(SecureToken.generate(), "not-a-token", invitation().id.toString(), invitation().tokenHash)
            .forEach { bogus ->
                postJson("/api/invitations/accept", """{"token":"$bogus"}""", login("bob@example.com").accessToken)
                    .andExpect(status().isNotFound)
            }

        // In particular: neither the row id nor the stored hash is the secret.
        assertThat(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, bob.id!!)).isNull()
    }

    @Test
    fun `an expired invitation is refused, and says so`() {
        val bob = newUser("bob@example.com")
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)
        expireInvitation(invitation().id!!)

        acceptAsExistingUser(token, bob)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.detail").value("This invitation has expired"))

        assertThat(roleOf(organizationId, bob)).isNull()
    }

    @Test
    fun `an expired invitation cannot be used to register either`() {
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)
        expireInvitation(invitation().id!!)

        registerWith(token, "bob@example.com").andExpect(status().isConflict)

        assertThat(userRepository.findByEmail("bob@example.com")).isNull()
    }

    @Test
    fun `a token is single use`() {
        val bob = newUser("bob@example.com")
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        acceptAsExistingUser(token, bob).andExpect(status().isOk)

        // Replaying it changes nothing - and it is the invitation's own state that says so,
        // independently of the membership index.
        acceptAsExistingUser(token, bob)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.detail").value("This invitation has already been accepted"))

        assertThat(organizationMemberRepository.findAll().count { it.userId == bob.id }).isEqualTo(1)
    }

    @Test
    fun `an accepted token cannot be reused by anybody, for registration or otherwise`() {
        val bob = newUser("bob@example.com")
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)
        acceptAsExistingUser(token, bob).andExpect(status().isOk)

        registerWith(token, "bob@example.com").andExpect(status().isConflict)
        registerWith(token, "someone-else@example.com").andExpect(status().isConflict)

        assertThat(userRepository.findByEmail("someone-else@example.com")).isNull()
    }

    // --- the organization went away in between -------------------------------------------------------

    @Test
    fun `an invitation into a closed organization is refused`() {
        val bob = newUser("bob@example.com")
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)
        // Closed directly rather than through DELETE: deleting also cancels the subscription,
        // which would drag a Stripe stand-in into a suite that is about invitations. The
        // tombstone is the only part of it this test cares about.
        closeOrganization(organizationId)

        // Joining a soft-deleted organization would create a membership OrganizationAccess
        // refuses to honour - a dead end the invitee could not diagnose.
        acceptAsExistingUser(token, bob).andExpect(status().isConflict)

        assertThat(roleOf(organizationId, bob)).isNull()
    }

    // --- what an acceptance grants ---------------------------------------------------------------------

    @Test
    fun `acceptance grants MEMBER and nothing more`() {
        val bob = newUser("bob@example.com")
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)
        acceptAsExistingUser(token, bob).andExpect(status().isOk)

        // A MEMBER, with a member's powers - the invitation is not a route to a privileged
        // role, whatever the inviter might have wanted.
        assertThat(roleOf(organizationId, bob)).isEqualTo(OrganizationRole.MEMBER)
        postJson("/api/organizations/$organizationId/projects", """{"name":"Nope"}""", bob.accessToken)
            .andExpect(status().isForbidden)
        postJson("/api/organizations/$organizationId/invitations", """{"email":"x@example.com"}""", bob.accessToken)
            .andExpect(status().isForbidden)
        assertThat(
            organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER),
        ).isEqualTo(1)
    }

    @Test
    fun `the inviter cannot smuggle a role through the request body`() {
        val bob = newUser("bob@example.com")

        val body = postJson(
            "/api/organizations/$organizationId/invitations",
            """{"email":"bob@example.com","role":"OWNER","organizationRole":"ADMIN"}""",
            owner.accessToken,
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        acceptAsExistingUser(JsonPath.read(body, "$.token"), bob).andExpect(status().isOk)

        assertThat(roleOf(organizationId, bob)).isEqualTo(OrganizationRole.MEMBER)
    }
}
