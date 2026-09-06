package com.tictac.io.billing

import com.tictac.io.organization.OrganizationRole
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Licences: acquired, occupied, vacated, reused and given up.
 *
 * The product rules these tests exist to pin down, in the order they matter:
 *
 * ```
 * member removal            ≠ licence removal
 * member + vacant licence   = assign the existing licence, bill nothing
 * member + no vacant        = acquire another licence, Stripe quantity + 1
 * explicit licence removal  = Stripe quantity - 1
 * ```
 *
 * The last one is the only way a licence is ever given up. Everything else leaves the count
 * exactly where it was.
 */
@DisplayName("Licences: acquired, occupied, vacated and reused")
class OrganizationLicenseLifecycleIntegrationTest : OrganizationApiTest() {

    @MockitoBean
    private lateinit var stripeGateway: StripeGateway

    private lateinit var owner: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        organizationId = createOrganization(owner, "Acme")

        whenever(stripeGateway.createCustomer(any(), any())).thenReturn("cus_test")
        whenever(stripeGateway.createCheckoutSession(any(), any(), any(), any()))
            .thenReturn(StripeSessionHandle("cs_test", "https://checkout.stripe.test/cs_test"))
    }

    private fun invite(email: String, caller: TestUser = owner) =
        postJson("/api/organizations/$organizationId/invitations", """{"email":"$email"}""", caller.accessToken)

    /** Invite and accept through the real endpoints. */
    private fun join(email: String, invitedBy: TestUser = owner): TestUser {
        val user = newUser(email)
        val token = inviteToOrganization(organizationId, email, invitedBy)

        postJson("/api/invitations/accept", """{"token":"$token"}""", user.accessToken)
            .andExpect(status().isOk)

        return user
    }

    private fun remove(member: TestUser, caller: TestUser = owner) =
        deleteRequest("/api/organizations/$organizationId/members/${member.id}", caller.accessToken)

    private fun setLicenses(licenses: Long, caller: TestUser = owner) =
        putJson("/api/organizations/$organizationId/licenses", """{"licenses":$licenses}""", caller.accessToken)

    private fun licenses(caller: TestUser = owner) =
        getRequest("/api/organizations/$organizationId/licenses", caller.accessToken)

    private fun subscriptionId() = "sub_test_$organizationId"

    private fun itemId() = "si_test_$organizationId"

    // --- the included licence ------------------------------------------------------------------

    @Test
    fun `a new organization holds one licence, occupied by its owner, and owes nothing`() {
        licenses()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.licenseCount").value(1))
            .andExpect(jsonPath("$.membersOccupying").value(1))
            .andExpect(jsonPath("$.vacantLicenses").value(0))
            .andExpect(jsonPath("$.paidLicenses").value(0))
            .andExpect(jsonPath("$.billedLicenses").value(0))
            .andExpect(jsonPath("$.subscriptionStatus").doesNotExist())

        // Not a subscription at quantity zero. Nothing at all.
        assertThat(subscriptionRepository.count()).isZero()
        assertThat(billingCustomerRepository.count()).isZero()
    }

    @Test
    fun `bringing somebody in needs a subscription, because there is no card to charge`() {
        invite("bob@example.com")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("No subscription to add a licence to"))
            .andExpect(jsonPath("$.licenseCount").value(1))

        // Refused before anything was written, so no dead link goes out.
        assertThat(organizationInvitationRepository.count()).isZero()
        assertThat(licenseCountOf(organizationId)).isEqualTo(1)
        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), any())
    }

    // --- assigning a vacant licence ----------------------------------------------------------------

    @Test
    fun `a vacant licence is reused and nothing is billed`() {
        subscribeOrganization(organizationId, licenses = 5)

        join("bob@example.com")
        join("carol@example.com")

        licenses()
            .andExpect(jsonPath("$.licenseCount").value(5))
            .andExpect(jsonPath("$.membersOccupying").value(3))
            .andExpect(jsonPath("$.vacantLicenses").value(2))
            .andExpect(jsonPath("$.licensesAvailable").value(2))

        // Three people moved into licences the organization already held. Stripe heard nothing.
        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), any())
        assertThat(billedLicensesOf(organizationId)).isEqualTo(4)
    }

    @Test
    fun `an outstanding invitation holds the vacant licence it was issued against`() {
        subscribeOrganization(organizationId, licenses = 2)

        // One vacant licence, and this invitation takes it.
        inviteToOrganization(organizationId, "bob@example.com", owner)

        licenses()
            .andExpect(jsonPath("$.vacantLicenses").value(1))
            .andExpect(jsonPath("$.pendingInvitations").value(1))
            // Vacant, but spoken for - which is the number that decides.
            .andExpect(jsonPath("$.licensesAvailable").value(0))

        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), any())
    }

    // --- acquiring a licence ------------------------------------------------------------------------

    @Test
    fun `inviting with no vacant licence acquires one on the existing subscription`() {
        subscribeOrganization(organizationId, licenses = 2)
        join("bob@example.com") // 2 members, 2 licences, none vacant

        inviteToOrganization(organizationId, "carol@example.com", owner)

        // The licence is bought when the invitation is issued, by the administrator issuing
        // it - so it is waiting before Carol ever clicks.
        verify(stripeGateway).updateSubscriptionQuantity(eq(subscriptionId()), eq(itemId()), eq(2L))
        assertThat(licenseCountOf(organizationId)).isEqualTo(3)
        assertThat(billedLicensesOf(organizationId)).isEqualTo(2)

        // Still one subscription. Never a second.
        assertThat(subscriptionRepository.count()).isEqualTo(1)
    }

    @Test
    fun `two invitations beyond capacity acquire two licences, not one`() {
        subscribeOrganization(organizationId, licenses = 1)

        inviteToOrganization(organizationId, "bob@example.com", owner)
        inviteToOrganization(organizationId, "carol@example.com", owner)

        // The second invitation cannot be given the licence the first is already holding.
        assertThat(licenseCountOf(organizationId)).isEqualTo(3)
        verify(stripeGateway).updateSubscriptionQuantity(any(), any(), eq(1L))
        verify(stripeGateway).updateSubscriptionQuantity(any(), any(), eq(2L))
    }

    @Test
    fun `the invitee's acceptance never moves the bill`() {
        subscribeOrganization(organizationId, licenses = 1)
        val bob = newUser("bob@example.com")
        val token = inviteToOrganization(organizationId, "bob@example.com", owner)

        assertThat(licenseCountOf(organizationId)).isEqualTo(2)

        postJson("/api/invitations/accept", """{"token":"$token"}""", bob.accessToken)
            .andExpect(status().isOk)

        // Exactly one Stripe call in the whole flow, and it happened at invite time.
        verify(stripeGateway).updateSubscriptionQuantity(any(), any(), eq(1L))
        assertThat(licenseCountOf(organizationId)).isEqualTo(2)
        assertThat(memberCountOf(organizationId)).isEqualTo(2)
    }

    @Test
    fun `an admin can acquire a licence by inviting`() {
        subscribeOrganization(organizationId, licenses = 2)
        val admin = newUser("admin@example.com").also { addMember(organizationId, it, OrganizationRole.ADMIN) }

        // Two members, two licences, none vacant - so this invitation buys one.
        inviteToOrganization(organizationId, "bob@example.com", admin)

        verify(stripeGateway).updateSubscriptionQuantity(any(), any(), eq(2L))
        assertThat(licenseCountOf(organizationId)).isEqualTo(3)
    }

    @Test
    fun `a Stripe failure while acquiring rolls the invitation back with it`() {
        subscribeOrganization(organizationId, licenses = 1)
        whenever(stripeGateway.updateSubscriptionQuantity(any(), any(), any()))
            .thenThrow(StripeUnavailableException("Stripe is down"))

        invite("bob@example.com").andExpect(status().isBadGateway)

        // No licence, no invitation, nothing half-written.
        assertThat(licenseCountOf(organizationId)).isEqualTo(1)
        assertThat(organizationInvitationRepository.count()).isZero()
    }

    // --- removing a member vacates, it does not remove -----------------------------------------------

    @Test
    fun `removing a member vacates their licence and leaves the bill alone`() {
        subscribeOrganization(organizationId, licenses = 5)
        val bob = join("bob@example.com")
        join("carol@example.com")

        remove(bob).andExpect(status().isNoContent)

        licenses()
            .andExpect(jsonPath("$.licenseCount").value(5))
            .andExpect(jsonPath("$.membersOccupying").value(2))
            .andExpect(jsonPath("$.vacantLicenses").value(3))
            .andExpect(jsonPath("$.billedLicenses").value(4))

        // The organization keeps paying for all five. Deliberate: an administrator removing a
        // colleague must not silently change what the company is charged, and the licence is
        // waiting for their replacement.
        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), any())
        verify(stripeGateway, never()).cancelSubscription(any())
    }

    @Test
    fun `the vacated licence is reused by the next person, free`() {
        subscribeOrganization(organizationId, licenses = 2)
        val bob = join("bob@example.com")

        remove(bob).andExpect(status().isNoContent)
        join("carol@example.com")

        assertThat(memberCountOf(organizationId)).isEqualTo(2)
        assertThat(licenseCountOf(organizationId)).isEqualTo(2)
        // Nobody bought anything: Carol is sitting in the licence Bob left.
        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), any())
    }

    // --- explicit licence removal ---------------------------------------------------------------------

    @Test
    fun `an administrator gives up a vacant licence and the bill drops`() {
        subscribeOrganization(organizationId, licenses = 5)
        join("bob@example.com")
        join("carol@example.com") // 3 members, 2 vacant

        setLicenses(4)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.licenseCount").value(4))
            .andExpect(jsonPath("$.vacantLicenses").value(1))
            .andExpect(jsonPath("$.paidLicenses").value(3))

        verify(stripeGateway).updateSubscriptionQuantity(eq(subscriptionId()), eq(itemId()), eq(3L))
        assertThat(licenseCountOf(organizationId)).isEqualTo(4)
        assertThat(memberCountOf(organizationId)).isEqualTo(3)
    }

    @Test
    fun `licences cannot be reduced below the members occupying them`() {
        subscribeOrganization(organizationId, licenses = 10)
        repeat(6) { join("member$it@example.com") } // 7 members, 3 vacant

        // Down to the occupied count is fine...
        setLicenses(9).andExpect(status().isOk)
        setLicenses(8).andExpect(status().isOk)
        setLicenses(7).andExpect(status().isOk)

        // ...and below it is not. Nobody is turned out to satisfy a reduction.
        setLicenses(6)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Licences are occupied"))
            .andExpect(jsonPath("$.minimumLicenses").value(7))
            .andExpect(jsonPath("$.licensesRequested").value(6))

        assertThat(licenseCountOf(organizationId)).isEqualTo(7)
        assertThat(memberCountOf(organizationId)).isEqualTo(7)
    }

    @Test
    fun `an occupied licence cannot be given up even when it is the only one over the floor`() {
        subscribeOrganization(organizationId, licenses = 3)
        join("bob@example.com")
        join("carol@example.com") // 3 members, 3 licences, 0 vacant

        setLicenses(2).andExpect(status().isConflict).andExpect(jsonPath("$.minimumLicenses").value(3))

        assertThat(licenseCountOf(organizationId)).isEqualTo(3)
        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), any())
    }

    @Test
    fun `raising the licence count charges the saved card with no checkout`() {
        subscribeOrganization(organizationId, licenses = 2)

        setLicenses(6)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.licenseCount").value(6))
            .andExpect(jsonPath("$.paidLicenses").value(5))
            .andExpect(jsonPath("$.vacantLicenses").value(5))

        // One API call against the existing subscription. No session, no URL, nothing for the
        // administrator to click through - Stripe bills the card already on file.
        verify(stripeGateway).updateSubscriptionQuantity(eq(subscriptionId()), eq(itemId()), eq(5L))
        verify(stripeGateway, never()).createCheckoutSession(any(), any(), any(), any())

        // ...and the room is immediately usable, without buying anything more.
        join("bob@example.com")
        join("carol@example.com")
        verify(stripeGateway).updateSubscriptionQuantity(any(), any(), any())
    }

    @Test
    fun `setting the same licence count again does nothing`() {
        subscribeOrganization(organizationId, licenses = 4)

        setLicenses(4).andExpect(status().isOk).andExpect(jsonPath("$.licenseCount").value(4))

        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), any())
    }

    @Test
    fun `giving up the last paid licence cancels the subscription`() {
        subscribeOrganization(organizationId, licenses = 4)

        setLicenses(1)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.licenseCount").value(1))
            .andExpect(jsonPath("$.paidLicenses").value(0))
            .andExpect(jsonPath("$.billedLicenses").value(0))

        // Cancelled rather than left running at quantity zero, which Stripe does not treat as
        // reliably free and which still reads as paid in the dashboard.
        verify(stripeGateway).cancelSubscription(eq(subscriptionId()))
        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), eq(0L))

        // The record survives; only the paid relationship ended.
        assertThat(subscriptionRepository.count()).isEqualTo(1)
        assertThat(licenseCountOf(organizationId)).isEqualTo(1)
    }

    @Test
    fun `licences cannot be changed before the organization has ever subscribed`() {
        setLicenses(3)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("No subscription yet"))

        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), any())
        assertThat(licenseCountOf(organizationId)).isEqualTo(1)
    }

    @Test
    fun `a Stripe failure while changing licences leaves the count untouched`() {
        subscribeOrganization(organizationId, licenses = 3)
        whenever(stripeGateway.updateSubscriptionQuantity(any(), any(), any()))
            .thenThrow(StripeUnavailableException("Stripe is down"))

        setLicenses(6).andExpect(status().isBadGateway)

        assertThat(licenseCountOf(organizationId)).isEqualTo(3)
        assertThat(billedLicensesOf(organizationId)).isEqualTo(2)
    }

    @Test
    fun `the licence count is validated`() {
        subscribeOrganization(organizationId, licenses = 3)

        listOf("""{}""", """{"licenses":null}""", """{"licenses":0}""", """{"licenses":-1}""", """{"licenses":100000}""")
            .forEach { body ->
                putJson("/api/organizations/$organizationId/licenses", body, owner.accessToken)
                    .andExpect(status().isBadRequest)
            }

        assertThat(licenseCountOf(organizationId)).isEqualTo(3)
    }

    // --- a subscription that ended --------------------------------------------------------------------

    @Test
    fun `a cancelled subscription keeps every member in place`() {
        subscribeOrganization(organizationId, licenses = 4)
        join("bob@example.com")
        join("carol@example.com")

        subscriptionRepository.findByOrganizationId(organizationId)!!
            .also { it.status = SubscriptionStatus.CANCELED }
            .let { subscriptionRepository.saveAndFlush(it) }

        // Nobody is removed and nothing is deleted - there are no paid features to withdraw.
        assertThat(memberCountOf(organizationId)).isEqualTo(3)
        licenses()
            .andExpect(jsonPath("$.licenseCount").value(4))
            .andExpect(jsonPath("$.membersOccupying").value(3))
            // What it holds, versus what is actually being billed. The gap is the whole story.
            .andExpect(jsonPath("$.paidLicenses").value(3))
            .andExpect(jsonPath("$.billedLicenses").value(0))
            .andExpect(jsonPath("$.subscriptionStatus").value("CANCELED"))
    }

    @Test
    fun `every product feature still works with no subscription at all`() {
        // The only thing money buys is licences. Nothing else is gated, ever.
        val projectId = createProject(organizationId, owner, "Website Redesign")
        val categoryId = createCategory(organizationId, projectId, owner, "Development")
        assignToProject(organizationId, projectId, owner, owner)
        val timerId = startTimer(organizationId, projectId, owner, categoryId = categoryId)

        postJson("/api/organizations/$organizationId/time-entries/$timerId/stop", "", owner.accessToken)
            .andExpect(status().isOk)

        assertThat(timeEntryRepository.count()).isEqualTo(1)
        assertThat(subscriptionRepository.count()).isZero()
    }

    @Test
    fun `a cancelled organization has the same features as a paying one`() {
        subscribeOrganization(organizationId, licenses = 3, status = SubscriptionStatus.CANCELED)

        val projectId = createProject(organizationId, owner, "Still Working")
        assignToProject(organizationId, projectId, owner, owner)
        val timerId = startTimer(organizationId, projectId, owner)

        postJson("/api/organizations/$organizationId/time-entries/$timerId/stop", "", owner.accessToken)
            .andExpect(status().isOk)
        getRequest("/api/organizations/$organizationId/projects", owner.accessToken)
            .andExpect(status().isOk)
    }

    // --- authorization -----------------------------------------------------------------------------------

    @Test
    fun `a plain member cannot change the licence count`() {
        subscribeOrganization(organizationId, licenses = 4)
        val member = newUser("member@example.com").also { addMember(organizationId, it, OrganizationRole.MEMBER) }

        setLicenses(8, caller = member).andExpect(status().isForbidden)
        putJson("/api/organizations/$organizationId/licenses", """{"licenses":8}""")
            .andExpect(status().isUnauthorized)

        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), any())
        assertThat(licenseCountOf(organizationId)).isEqualTo(4)
    }

    @Test
    fun `an admin may change the licence count, since inviting already acquires them`() {
        subscribeOrganization(organizationId, licenses = 4)
        val admin = newUser("admin@example.com").also { addMember(organizationId, it, OrganizationRole.ADMIN) }

        setLicenses(6, caller = admin).andExpect(status().isOk)

        assertThat(licenseCountOf(organizationId)).isEqualTo(6)
    }

    @Test
    fun `every member can read the licence state`() {
        subscribeOrganization(organizationId, licenses = 3)
        val member = newUser("member@example.com").also { addMember(organizationId, it, OrganizationRole.MEMBER) }

        licenses(caller = member).andExpect(status().isOk).andExpect(jsonPath("$.licenseCount").value(3))
    }

    @Test
    fun `one organization's licences cannot be read or changed from another`() {
        subscribeOrganization(organizationId, licenses = 3)
        val otherOwner = newUser("other-owner@example.com")
        val other = createOrganization(otherOwner, "Other Company")
        subscribeOrganization(other, licenses = 9)

        putJson("/api/organizations/$other/licenses", """{"licenses":2}""", owner.accessToken)
            .andExpect(status().isNotFound)
        getRequest("/api/organizations/$other/licenses", owner.accessToken)
            .andExpect(status().isNotFound)

        assertThat(licenseCountOf(other)).isEqualTo(9)
        verify(stripeGateway, never()).updateSubscriptionQuantity(any(), any(), any())
    }

    @Test
    fun `an outsider is not told the organization exists`() {
        subscribeOrganization(organizationId, licenses = 3)
        val outsider = newUser("outsider@example.com")

        licenses(caller = outsider).andExpect(status().isNotFound)
        setLicenses(9, caller = outsider).andExpect(status().isNotFound)
    }
}
