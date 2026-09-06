package com.tictac.io.organization

import com.jayway.jsonpath.JsonPath
import com.tictac.io.common.security.SecureToken
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

@DisplayName("Organization invitations: issuing")
class OrganizationInvitationApiIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var member: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        member = newUser("member@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, member, OrganizationRole.MEMBER)
    }

    private fun invitationsPath(organization: UUID = organizationId) =
        "/api/organizations/$organization/invitations"

    private fun invite(email: String, caller: TestUser, organization: UUID = organizationId) =
        postJson(invitationsPath(organization), """{"email":"$email"}""", caller.accessToken)

    // --- who may invite ------------------------------------------------------------------

    @Test
    fun `an owner can invite, and the invitation records the organization and the inviter`() {
        val body = invite("bob@example.com", owner)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value("bob@example.com"))
            .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
            .andExpect(jsonPath("$.invitedByUserId").value(owner.id.toString()))
            .andExpect(jsonPath("$.token").isNotEmpty)
            .andExpect(jsonPath("$.expiresAt").isNotEmpty)
            .andReturn().response.contentAsString

        val invitation = organizationInvitationRepository
            .findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow()

        assertThat(invitation.organizationId).isEqualTo(organizationId)
        assertThat(invitation.invitedByUserId).isEqualTo(owner.id)
        assertThat(invitation.email).isEqualTo("bob@example.com")
        assertThat(invitation.acceptedAt).isNull()
        assertThat(invitation.expiresAt).isAfter(Instant.now())
    }

    @Test
    fun `an admin can invite`() {
        invite("bob@example.com", admin)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.invitedByUserId").value(admin.id.toString()))
    }

    @Test
    fun `a member cannot invite`() {
        invite("bob@example.com", member).andExpect(status().isForbidden)

        assertThat(organizationInvitationRepository.count()).isZero()
    }

    @Test
    fun `an unauthenticated request cannot invite`() {
        postJson(invitationsPath(), """{"email":"bob@example.com"}""")
            .andExpect(status().isUnauthorized)

        assertThat(organizationInvitationRepository.count()).isZero()
    }

    @Test
    fun `a non-member cannot invite, and is not told the organization exists`() {
        val outsider = newUser("outsider@example.com")

        invite("bob@example.com", outsider).andExpect(status().isNotFound)

        assertThat(organizationInvitationRepository.count()).isZero()
    }

    @Test
    fun `the owner of one organization cannot invite into another`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")

        invite("bob@example.com", owner, organization = otherOrganization)
            .andExpect(status().isNotFound)

        assertThat(organizationInvitationRepository.count()).isZero()
    }

    // --- the address ---------------------------------------------------------------------

    @Test
    fun `an invalid email is rejected`() {
        listOf("""{}""", """{"email":null}""", """{"email":""}""", """{"email":"not-an-email"}""")
            .forEach { postJson(invitationsPath(), it, owner.accessToken).andExpect(status().isBadRequest) }

        assertThat(organizationInvitationRepository.count()).isZero()
    }

    @Test
    fun `the address is normalised exactly as registration normalises it`() {
        invite("BOB@Example.COM", owner)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value("bob@example.com"))

        // Stored in the same form as users.email, or no account could ever match it.
        assertThat(organizationInvitationRepository.findAll().single().email).isEqualTo("bob@example.com")
    }

    @Test
    fun `an address with surrounding whitespace is refused, exactly as registration refuses it`() {
        // Not a normalisation gap: @Email runs before normalizeEmail on both endpoints, so
        // an invitation accepts precisely the addresses registration accepts. An invitation
        // to an address no account could be created at would be useless.
        postJson(invitationsPath(), """{"email":"  bob@example.com  "}""", owner.accessToken)
            .andExpect(status().isBadRequest)
        postJson("/api/auth/register", REGISTRATION_WITH_PADDED_EMAIL)
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `an address that differs only in case is the same address`() {
        invite("bob@example.com", owner).andExpect(status().isCreated)

        invite("BOB@EXAMPLE.COM", owner).andExpect(status().isConflict)

        assertThat(organizationInvitationRepository.count()).isEqualTo(1)
    }

    // --- who is worth inviting -------------------------------------------------------------

    @Test
    fun `someone already in the organization cannot be invited`() {
        invite("member@example.com", owner)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Already a member"))

        // No useless invitation, and certainly no second membership.
        assertThat(organizationInvitationRepository.count()).isZero()
        assertThat(organizationMemberRepository.findAll().count { it.userId == member.id }).isEqualTo(1)
    }

    @Test
    fun `an existing user who is not a member can be invited`() {
        val existing = newUser("existing@example.com")

        invite("existing@example.com", owner)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value("existing@example.com"))

        // No second account is created; the invitation simply names their address.
        assertThat(userRepository.findByEmail("existing@example.com")!!.id).isEqualTo(existing.id)
        assertThat(roleOf(organizationId, existing)).isNull()
    }

    @Test
    fun `an address with no account at all can be invited`() {
        invite("nobody@example.com", owner).andExpect(status().isCreated)

        assertThat(userRepository.findByEmail("nobody@example.com")).isNull()
    }

    @Test
    fun `the same address can be invited to two different organizations`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")

        invite("bob@example.com", owner).andExpect(status().isCreated)
        invite("bob@example.com", otherOwner, organization = otherOrganization).andExpect(status().isCreated)

        assertThat(organizationInvitationRepository.count()).isEqualTo(2)
    }

    // --- duplicates ---------------------------------------------------------------------------

    @Test
    fun `a second pending invitation to the same address is refused`() {
        invite("bob@example.com", owner).andExpect(status().isCreated)

        invite("bob@example.com", owner)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Invitation already pending"))
        invite("bob@example.com", admin).andExpect(status().isConflict)

        assertThat(organizationInvitationRepository.count()).isEqualTo(1)
    }

    @Test
    fun `an expired invitation can be replaced`() {
        val first = invitationIdFrom(invite("bob@example.com", owner).andExpect(status().isCreated))
        expireInvitation(first)

        val body = invite("bob@example.com", owner)
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val second = UUID.fromString(JsonPath.read(body, "$.id"))

        // The lapsed one is cleared out so the partial unique index has room; what remains is
        // one live invitation rather than two nobody could tell apart.
        assertThat(second).isNotEqualTo(first)
        assertThat(organizationInvitationRepository.findById(first)).isEmpty
        assertThat(organizationInvitationRepository.count()).isEqualTo(1)
    }

    @Test
    fun `the database refuses a second open invitation even with the service out of the way`() {
        invite("bob@example.com", owner).andExpect(status().isCreated)

        // The application checks first for a clean 409, but the partial unique index on
        // (organization_id, email) WHERE accepted_at IS NULL is the actual guarantee - two
        // administrators inviting at the same instant both pass the check.
        org.assertj.core.api.Assertions.assertThatThrownBy {
            organizationInvitationRepository.saveAndFlush(
                OrganizationInvitation(
                    organizationId = organizationId,
                    email = "bob@example.com",
                    invitedByUserId = owner.id,
                    tokenHash = SecureToken.hash(SecureToken.generate()),
                    expiresAt = Instant.now().plus(Duration.ofDays(7)),
                ),
            )
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)

        assertThat(organizationInvitationRepository.count()).isEqualTo(1)
    }

    // --- the token ------------------------------------------------------------------------------

    @Test
    fun `the raw token is never stored`() {
        val body = invite("bob@example.com", owner).andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val rawToken = JsonPath.read<String>(body, "$.token")

        val invitation = organizationInvitationRepository.findAll().single()

        // What is stored is the hash, and only the hash.
        assertThat(invitation.tokenHash).isNotEqualTo(rawToken)
        assertThat(invitation.tokenHash).isEqualTo(SecureToken.hash(rawToken))
        assertThat(invitation.tokenHash).hasSize(SecureToken.HASH_LENGTH)

        // Nothing anywhere in the row carries the raw value, including its toString - which
        // is what would leak it into a log line or an exception message.
        assertThat(invitation.toString()).doesNotContain(rawToken)
        assertThat(invitation.toString()).doesNotContain(invitation.tokenHash)
    }

    @Test
    fun `tokens are unpredictable and URL-safe`() {
        val tokens = (1..10).map {
            JsonPath.read<String>(
                invite("bob$it@example.com", owner).andExpect(status().isCreated)
                    .andReturn().response.contentAsString,
                "$.token",
            )
        }

        assertThat(tokens).doesNotHaveDuplicates()
        // 256 bits of CSPRNG, unpadded URL-safe Base64 - so it survives being pasted into a
        // link without escaping, and is nowhere near guessable.
        assertThat(tokens).allSatisfy { assertThat(it).matches("[A-Za-z0-9_-]{43}") }
        // Emphatically not the row id, and not a JWT.
        val ids = organizationInvitationRepository.findAll().map { it.id.toString() }
        assertThat(tokens).doesNotContainAnyElementsOf(ids)
        assertThat(tokens).allSatisfy { assertThat(it).doesNotContain(".") }
    }

    @Test
    fun `the expiry is a week out by default`() {
        invite("bob@example.com", owner).andExpect(status().isCreated)

        val invitation = organizationInvitationRepository.findAll().single()
        assertThat(Duration.between(invitation.createdAt, invitation.expiresAt))
            .isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7).plusMinutes(1))
    }

    private fun invitationIdFrom(result: org.springframework.test.web.servlet.ResultActions): UUID =
        UUID.fromString(JsonPath.read(result.andReturn().response.contentAsString, "$.id"))

    private companion object {
        const val REGISTRATION_WITH_PADDED_EMAIL =
            """{"firstName":"Bob","lastName":"Smith","email":"  bob@example.com  ",""" +
                """"password":"correct-horse-battery"}"""
    }
}
