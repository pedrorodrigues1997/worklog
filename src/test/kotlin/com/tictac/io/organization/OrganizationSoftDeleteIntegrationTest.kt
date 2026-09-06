package com.tictac.io.organization

import com.tictac.io.billing.StripeGateway
import com.tictac.io.billing.StripeUnavailableException
import com.tictac.io.billing.SubscriptionStatus
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
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

@DisplayName("Organization soft deletion")
class OrganizationSoftDeleteIntegrationTest : OrganizationApiTest() {

    @MockitoBean
    private lateinit var stripeGateway: StripeGateway

    private lateinit var owner: TestUser
    private lateinit var member: TestUser
    private var organizationId = UUID.randomUUID()

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        member = newUser("member@example.com")

        organizationId = createOrganization(owner, "Acme")
        addMember(organizationId, member, OrganizationRole.MEMBER)
    }

    @Test
    fun `the owner soft deletes the organization rather than destroying it`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        val organization = organizationRepository.findById(organizationId).orElseThrow()
        assertThat(organization.deletedAt).isNotNull()
        assertThat(organization.name).isEqualTo("Acme")
    }

    @Test
    fun `a deleted organization disappears from every member's listing`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        listOf(owner, member).forEach { user ->
            getRequest("/api/organizations", user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(0)))
        }
    }

    @Test
    fun `a deleted organization cannot be reached by anyone, including its owner`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        listOf(owner, member).forEach { user ->
            getRequest("/api/organizations/$organizationId", user.accessToken)
                .andExpect(status().isNotFound)
            patchJson("/api/organizations/$organizationId", """{"name":"Revived"}""", user.accessToken)
                .andExpect(status().isNotFound)
            getRequest("/api/organizations/$organizationId/members", user.accessToken)
                .andExpect(status().isNotFound)
            patchJson(
                "/api/organizations/$organizationId/members/${member.id}",
                """{"role":"ADMIN"}""",
                user.accessToken,
            ).andExpect(status().isNotFound)
            deleteRequest("/api/organizations/$organizationId/members/${member.id}", user.accessToken)
                .andExpect(status().isNotFound)
        }

        assertThat(organizationRepository.findById(organizationId).orElseThrow().name).isEqualTo("Acme")
    }

    @Test
    fun `deleting twice is a not found the second time`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `deleting the organization destroys none of its data`() {
        val deletedAtBefore = organizationRepository.findById(organizationId).orElseThrow().deletedAt
        assertThat(deletedAtBefore).isNull()

        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        // Memberships survive: the organization is closed, not purged, and reopening it
        // must not require rebuilding the member list from nothing.
        assertThat(roleOf(organizationId, owner)).isEqualTo(OrganizationRole.OWNER)
        assertThat(roleOf(organizationId, member)).isEqualTo(OrganizationRole.MEMBER)
        assertThat(userRepository.findById(member.id)).isPresent
    }

    @Test
    fun `deleting one organization leaves the caller's others alone`() {
        val second = createOrganization(owner, "Second")

        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        getRequest("/api/organizations", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].id").value(second.toString()))
    }

    // --- deleting an organization stops the money ------------------------------------------

    @Test
    fun `deleting an organization stops its subscription renewing`() {
        subscribeOrganization(organizationId, licenses = 5)

        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        // At period end, not now: the customer has already paid for this period, and taking it
        // away - or refunding it unasked - is a worse answer than simply not charging again.
        verify(stripeGateway).cancelSubscriptionAtPeriodEnd(eq("sub_test_$organizationId"))
        verify(stripeGateway, never()).cancelSubscription(any())

        val subscription = subscriptionRepository.findByOrganizationId(organizationId)!!
        assertThat(subscription.cancelAtPeriodEnd).isTrue()
        // Requested now; it has not actually ended, so Stripe still calls it active and so do we.
        assertThat(subscription.cancelledAt).isNotNull()
        assertThat(subscription.status).isEqualTo(SubscriptionStatus.ACTIVE)
    }

    @Test
    fun `an owner can ask for immediate cancellation instead`() {
        subscribeOrganization(organizationId, licenses = 5)

        deleteRequest(
            "/api/organizations/$organizationId?cancelImmediately=true",
            owner.accessToken,
        ).andExpect(status().isNoContent)

        verify(stripeGateway).cancelSubscription(eq("sub_test_$organizationId"))
        verify(stripeGateway, never()).cancelSubscriptionAtPeriodEnd(any())

        val subscription = subscriptionRepository.findByOrganizationId(organizationId)!!
        assertThat(subscription.status).isEqualTo(SubscriptionStatus.CANCELED)
        assertThat(subscription.paidLicenses).isZero()
    }

    @Test
    fun `deleting a free organization never touches Stripe`() {
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        // Nothing was ever bought, so there is nothing to cancel - and no reason for a
        // deployment without Stripe configured to fail on a delete.
        verify(stripeGateway, never()).cancelSubscriptionAtPeriodEnd(any())
        verify(stripeGateway, never()).cancelSubscription(any())
        assertThat(organizationRepository.findById(organizationId).orElseThrow().deletedAt).isNotNull()
    }

    @Test
    fun `deleting an organization whose subscription already ended is not cancelled twice`() {
        subscribeOrganization(organizationId, licenses = 3, status = SubscriptionStatus.CANCELED)

        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        verify(stripeGateway, never()).cancelSubscriptionAtPeriodEnd(any())
        verify(stripeGateway, never()).cancelSubscription(any())
    }

    @Test
    fun `a Stripe failure leaves the organization alive so the owner can retry`() {
        subscribeOrganization(organizationId, licenses = 5)
        whenever(stripeGateway.cancelSubscriptionAtPeriodEnd(any()))
            .thenThrow(StripeUnavailableException("Stripe is down"))

        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isBadGateway)

        // The right way round. An organization that is still alive can be deleted again; one
        // that is unreachable *and* still being billed cannot be fixed through the API at all.
        assertThat(organizationRepository.findById(organizationId).orElseThrow().deletedAt).isNull()
        assertThat(subscriptionRepository.findByOrganizationId(organizationId)!!.cancelAtPeriodEnd).isFalse()
        getRequest("/api/organizations/$organizationId", owner.accessToken).andExpect(status().isOk)
    }

    @Test
    fun `the licences it held are left on the record`() {
        subscribeOrganization(organizationId, licenses = 5)

        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        // Nothing is destroyed, including what it was paying for. The subscription row and the
        // licence count both survive; only the renewal stops.
        assertThat(licenseCountOf(organizationId)).isEqualTo(5)
        assertThat(subscriptionRepository.count()).isEqualTo(1)
        assertThat(memberCountOf(organizationId)).isEqualTo(2)
    }

    @Test
    fun `billing is unreachable once the organization is deleted`() {
        subscribeOrganization(organizationId, licenses = 5)

        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNoContent)

        // This is *why* cancellation has to happen during the delete: afterwards every billing
        // endpoint goes through the gate that now refuses the tenant.
        getRequest("/api/organizations/$organizationId/licenses", owner.accessToken)
            .andExpect(status().isNotFound)
        getRequest("/api/organizations/$organizationId/subscription", owner.accessToken)
            .andExpect(status().isNotFound)
        postJson("/api/organizations/$organizationId/billing/portal", "", owner.accessToken)
            .andExpect(status().isNotFound)
        deleteRequest("/api/organizations/$organizationId", owner.accessToken)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a member cannot delete the organization, and nothing is cancelled`() {
        subscribeOrganization(organizationId, licenses = 5)

        deleteRequest("/api/organizations/$organizationId", member.accessToken)
            .andExpect(status().isForbidden)

        verify(stripeGateway, never()).cancelSubscriptionAtPeriodEnd(any())
        assertThat(organizationRepository.findById(organizationId).orElseThrow().deletedAt).isNull()
    }
}
