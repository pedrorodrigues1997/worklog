package com.tictac.io.organization

import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@DisplayName("Ownership transfer")
class OrganizationOwnershipTransferIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private lateinit var admin: TestUser
    private lateinit var member: TestUser
    private lateinit var outsider: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        admin = newUser("admin@example.com")
        member = newUser("member@example.com")
        outsider = newUser("outsider@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, admin, OrganizationRole.ADMIN)
        addMember(organizationId, member, OrganizationRole.MEMBER)
    }

    private fun transferTo(target: UUID, caller: TestUser, organization: UUID = organizationId) =
        postJson(
            "/api/organizations/$organization/transfer-ownership",
            """{"userId":"$target"}""",
            caller.accessToken,
        )

    // --- the happy path -------------------------------------------------------------

    @Test
    fun `the owner can hand the organization to a member, who becomes OWNER`() {
        transferTo(member.id, caller = owner)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
            .andExpect(jsonPath("$.newOwner.userId").value(member.id.toString()))
            .andExpect(jsonPath("$.newOwner.role").value("OWNER"))
            .andExpect(jsonPath("$.newOwner.email").value("member@example.com"))
            .andExpect(jsonPath("$.previousOwner.userId").value(owner.id.toString()))
            .andExpect(jsonPath("$.previousOwner.role").value("ADMIN"))

        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.ADMIN)
    }

    @Test
    fun `the previous owner becomes ADMIN, not MEMBER`() {
        transferTo(admin.id, caller = owner).andExpect(status().isOk)

        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.ADMIN)
    }

    @Test
    fun `there is exactly one OWNER before and after, checked in the database`() {
        assertThat(ownerCount()).isEqualTo(1)

        transferTo(member.id, caller = owner).andExpect(status().isOk)

        assertThat(ownerCount()).isEqualTo(1)
        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.OWNER)

        // Membership rows are updated in place - a transfer must not leave a spare row
        // behind, which is the other way a second owner could appear.
        assertThat(organizationMemberRepository.findMembersOfOrganization(organizationId)).hasSize(3)
    }

    @Test
    fun `the transfer succeeds at all only because the demote is written before the promote`() {
        // The partial unique index permits one OWNER row per organization at any instant.
        // Promoting first would violate it on every single transfer, so a green happy path
        // is itself the assertion that the statement order is right.
        transferTo(member.id, caller = owner).andExpect(status().isOk)

        assertThat(ownerCount()).isEqualTo(1)
    }

    @Test
    fun `the new owner gains owner powers and the old owner loses them`() {
        transferTo(member.id, caller = owner).andExpect(status().isOk)

        // Deleting the organization is OWNER-only. The old owner is an ADMIN now...
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isForbidden)
        assertThat(organizationRepository.findById(organizationId).orElseThrow().deletedAt).isNull()

        // ...and the new owner has it.
        deleteRequest("/api/organizations/$organizationId", member.accessToken)
            .andExpect(status().isNoContent)
        assertThat(organizationRepository.findById(organizationId).orElseThrow().deletedAt).isNotNull()
    }

    @Test
    fun `the previous owner can no longer transfer, and the new owner can transfer back`() {
        transferTo(member.id, caller = owner).andExpect(status().isOk)

        // The old owner is an ADMIN now, and ADMINs may not transfer.
        transferTo(owner.id, caller = owner).andExpect(status().isForbidden)

        transferTo(owner.id, caller = member).andExpect(status().isOk)

        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.ADMIN)
        assertThat(ownerCount()).isEqualTo(1)
    }

    // --- authorization ---------------------------------------------------------------

    @Test
    fun `a member cannot transfer ownership`() {
        transferTo(admin.id, caller = member).andExpect(status().isForbidden)

        assertUnchanged()
    }

    @Test
    fun `an admin cannot transfer ownership`() {
        transferTo(member.id, caller = admin).andExpect(status().isForbidden)

        assertUnchanged()
    }

    @Test
    fun `a non-member cannot transfer ownership, and is not told the organization exists`() {
        transferTo(member.id, caller = outsider).andExpect(status().isNotFound)

        assertUnchanged()
    }

    @Test
    fun `transfer requires authentication`() {
        postJson("/api/organizations/$organizationId/transfer-ownership", """{"userId":"${member.id}"}""")
            .andExpect(status().isUnauthorized)

        assertUnchanged()
    }

    @Test
    fun `the owner of one organization cannot transfer another`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")
        val otherMember = newUser("other-member@example.com")
        addMember(otherOrganization, otherMember, OrganizationRole.MEMBER)

        // A perfectly valid target - just not in an organization this caller belongs to.
        transferTo(otherMember.id, caller = owner, organization = otherOrganization)
            .andExpect(status().isNotFound)

        assertThat(roleOf(otherOrganization, otherOwner)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(otherOrganization, otherMember)).isEqualTo(OrganizationRole.MEMBER)
        assertUnchanged()
    }

    // --- rejected targets -------------------------------------------------------------

    @Test
    fun `self-transfer is rejected as a validation error rather than a silent no-op`() {
        transferTo(owner.id, caller = owner)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.title").value("Invalid ownership transfer"))

        assertUnchanged()
    }

    @Test
    fun `a target who is not a member of this organization is rejected`() {
        transferTo(outsider.id, caller = owner).andExpect(status().isNotFound)

        assertThat(roleOf(organizationId, outsider)).isNull()
        assertUnchanged()
    }

    @Test
    fun `a member of a different organization is rejected`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")

        transferTo(otherOwner.id, caller = owner).andExpect(status().isNotFound)

        assertThat(roleOf(otherOrganization, otherOwner)).isEqualTo(OrganizationRole.OWNER)
        assertUnchanged()
    }

    @Test
    fun `a target user that does not exist is rejected`() {
        transferTo(UUID.randomUUID(), caller = owner).andExpect(status().isNotFound)

        assertUnchanged()
    }

    @Test
    fun `a closed account cannot be made owner`() {
        // The member closes their own account - allowed, they own nothing - which leaves
        // their membership row in place. That row must not become a route to ownership.
        deleteRequest("/api/users/me", member.accessToken).andExpect(status().isNoContent)
        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)

        transferTo(member.id, caller = owner)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Ownership transfer failed"))

        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(ownerCount()).isEqualTo(1)
    }

    @Test
    fun `a transfer into a soft-deleted organization is refused`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken).andExpect(status().isNoContent)

        transferTo(member.id, caller = owner).andExpect(status().isNotFound)

        assertUnchanged()
    }

    // --- request validation ------------------------------------------------------------

    @Test
    fun `the target user id is required and must be a uuid`() {
        listOf("""{}""", """{"userId":null}""", """{"userId":""}""", """{"userId":"not-a-uuid"}""")
            .forEach { body ->
                postJson("/api/organizations/$organizationId/transfer-ownership", body, owner.accessToken)
                    .andExpect(status().isBadRequest)
            }

        assertUnchanged()
    }

    private fun ownerCount() =
        organizationMemberRepository.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER)

    private fun assertUnchanged() {
        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(organizationId, admin)).isEqualTo(OrganizationRole.ADMIN)
        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(ownerCount()).isEqualTo(1)
    }
}
