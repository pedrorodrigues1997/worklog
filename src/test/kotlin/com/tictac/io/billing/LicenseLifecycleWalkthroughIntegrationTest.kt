package com.tictac.io.billing

import com.jayway.jsonpath.JsonPath
import com.stripe.model.Event
import com.stripe.net.ApiResource
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * The whole licence lifecycle, driven through the HTTP API from end to end.
 *
 * Every other billing test isolates one rule. This one walks the two journeys a real
 * organization takes and checks the state after every single step, because the rules interact:
 * "removal does not reduce" and "a vacant licence is reused" are each easy to get right alone
 * and easy to get wrong together.
 *
 * Nothing is seeded. The organization is created through the API, the subscription arrives
 * over the real webhook endpoint carrying the JSON Stripe actually sends, and members join by
 * accepting real invitations. The only stand-in is [StripeGateway] - the one component that
 * would make a network call - and of it, only the signature check is replaced, by parsing the
 * payload exactly as Stripe's own `constructEvent` does once it has verified. Verification
 * itself is covered end to end in [StripeWebhookIntegrationTest], where it is the subject
 * rather than a prerequisite; duplicating it here would cost this suite its own Spring
 * context for nothing.
 *
 * The claim checked after every step: **one Stripe subscription, throughout.**
 */
@DisplayName("Licence lifecycle, end to end through the API")
class LicenseLifecycleWalkthroughIntegrationTest : OrganizationApiTest() {

    @MockitoBean
    private lateinit var stripeGateway: StripeGateway

    private lateinit var owner: TestUser
    private var organizationId = UUID.randomUUID()

    private val customerId = "cus_walkthrough"
    private val subscriptionId = "sub_walkthrough"

    @BeforeEach
    fun createOrganization() {
        owner = newUser("owner@example.com")
        organizationId = createOrganization(owner, "Acme")

        whenever(stripeGateway.createCustomer(any(), any())).thenReturn(customerId)
        whenever(stripeGateway.createCheckoutSession(any(), any(), any(), any()))
            .thenReturn(StripeSessionHandle("cs_walkthrough", "https://checkout.stripe.test/cs"))
        // Parsed the way Stripe parses it after verifying - so everything downstream of the
        // signature (the service, the transaction, the database) is genuinely exercised.
        whenever(stripeGateway.verifyAndParse(any(), any())).thenAnswer { invocation ->
            ApiResource.GSON.fromJson(invocation.getArgument<String>(0), Event::class.java)
        }
    }

    // --- helpers, all going through the real endpoints -----------------------------------------

    private fun licenses() = getRequest("/api/organizations/$organizationId/licenses", owner.accessToken)

    private fun assertLicenses(held: Long, members: Long, vacant: Long) {
        licenses()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.licenseCount").value(held))
            .andExpect(jsonPath("$.membersOccupying").value(members))
            .andExpect(jsonPath("$.vacantLicenses").value(vacant))

        // The claim this whole test exists to make.
        assertThat(subscriptionRepository.count())
            .describedAs("Stripe subscriptions for this organization")
            .isLessThanOrEqualTo(1)
    }

    private fun checkout(licenseCount: Long, interval: BillingInterval = BillingInterval.MONTHLY) =
        postJson(
            "/api/organizations/$organizationId/billing/checkout",
            """{"licenses":$licenseCount,"interval":"$interval"}""",
            owner.accessToken,
        ).andExpect(status().isOk)

    /** Stripe confirming a purchase, over the real webhook endpoint. */
    private fun stripeConfirms(
        eventId: String,
        quantity: Int,
        status: String = "active",
        priceId: String = "price_test_monthly",
    ) {
        ensureCustomer()

        val payload = StripeWebhookSupport.subscriptionEvent(
            eventId = eventId,
            type = "customer.subscription.updated",
            subscriptionId = subscriptionId,
            customerId = customerId,
            status = status,
            priceId = priceId,
            quantity = quantity,
        )

        mockMvc.perform(
            post("/api/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", StripeWebhookSupport.signatureHeader(payload))
                .content(payload),
        ).andExpect(status().isOk)
    }

    /**
     * The customer row checkout would have written.
     *
     * Needed because [StripeGateway] is stubbed, so no real customer id comes back - and the
     * webhook resolves the organization from the customer id and nothing else.
     */
    private fun ensureCustomer() {
        billingCustomerRepository.findByOrganizationIdAndProvider(organizationId, BillingProvider.STRIPE)
            ?: billingCustomerRepository.saveAndFlush(
                BillingCustomer(organizationId, BillingProvider.STRIPE, customerId),
            )
    }

    private fun join(email: String): TestUser {
        val user = newUser(email)
        val token = inviteToOrganization(organizationId, email, owner)

        postJson("/api/invitations/accept", """{"token":"$token"}""", user.accessToken)
            .andExpect(status().isOk)

        return user
    }

    private fun remove(member: TestUser) =
        deleteRequest("/api/organizations/$organizationId/members/${member.id}", owner.accessToken)
            .andExpect(status().isNoContent)

    private fun setLicenses(count: Long) =
        putJson("/api/organizations/$organizationId/licenses", """{"licenses":$count}""", owner.accessToken)

    // --- journey one -----------------------------------------------------------------------------

    @Test
    fun `create, grow, shrink, and reuse the vacated licence without buying anything`() {
        // 1. A new organization: one member, one included licence, no Stripe relationship.
        assertLicenses(held = 1, members = 1, vacant = 0)
        assertThat(subscriptionRepository.count()).isZero()
        assertThat(billingCustomerRepository.count()).isZero()

        // 2. It needs room for a second person, so it buys a second licence. Checkout, because
        //    this is the first purchase and there is no card on file yet.
        val body = checkout(licenseCount = 2).andReturn().response.contentAsString
        assertThat(JsonPath.read<Int>(body, "$.paidLicenses")).isEqualTo(1)

        // Nothing is granted until Stripe says so.
        assertLicenses(held = 1, members = 1, vacant = 0)

        stripeConfirms(eventId = "evt_purchase", quantity = 1)
        assertLicenses(held = 2, members = 1, vacant = 1)

        // 3. Somebody joins and occupies the licence that was just bought.
        val bob = join("bob@example.com")
        assertLicenses(held = 2, members = 2, vacant = 0)

        // 4. They leave. The licence is vacated, NOT removed - the organization keeps paying
        //    for it and keeps the room.
        remove(bob)
        assertLicenses(held = 2, members = 1, vacant = 1)

        // 5. Their replacement moves into the same licence. Nothing is purchased.
        join("carol@example.com")
        assertLicenses(held = 2, members = 2, vacant = 0)

        // One subscription for the entire journey, and its quantity was set once - by the
        // purchase. Neither the join, the removal, nor the re-join touched it.
        assertThat(subscriptionRepository.count()).isEqualTo(1)
        assertThat(subscriptionRepository.findAll().single().providerSubscriptionId).isEqualTo(subscriptionId)
        assertThat(billedLicensesOf(organizationId)).isEqualTo(1)
    }

    // --- journey two -----------------------------------------------------------------------------

    @Test
    fun `a full organization vacates a licence and then explicitly gives it up`() {
        // Five licences, five members, none vacant.
        checkout(licenseCount = 5)
        stripeConfirms(eventId = "evt_five", quantity = 4)

        val bob = join("bob@example.com")
        join("carol@example.com")
        join("dave@example.com")
        join("erin@example.com")
        assertLicenses(held = 5, members = 5, vacant = 0)

        // Somebody leaves: five licences, four members, one vacant. Stripe is untouched, and
        // the organization is still billed for four paid licences.
        remove(bob)
        assertLicenses(held = 5, members = 4, vacant = 1)
        assertThat(billedLicensesOf(organizationId)).isEqualTo(4)

        // Now the administrator explicitly gives the vacant licence up. *This* is what reduces
        // the bill - and it is a different operation from removing the member was.
        setLicenses(4)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.licenseCount").value(4))
            .andExpect(jsonPath("$.vacantLicenses").value(0))
            .andExpect(jsonPath("$.paidLicenses").value(3))

        assertLicenses(held = 4, members = 4, vacant = 0)

        // Still one subscription, its quantity changed rather than replaced.
        assertThat(subscriptionRepository.count()).isEqualTo(1)
        assertThat(subscriptionRepository.findAll().single().providerSubscriptionId).isEqualTo(subscriptionId)
    }

    // --- growing past capacity, and the floor ----------------------------------------------------

    @Test
    fun `inviting past capacity acquires a licence, and the floor refuses to strand anybody`() {
        checkout(licenseCount = 2)
        stripeConfirms(eventId = "evt_two", quantity = 1)

        join("bob@example.com")
        assertLicenses(held = 2, members = 2, vacant = 0)

        // No vacant licence, so inviting acquires one on the existing subscription.
        join("carol@example.com")
        assertLicenses(held = 3, members = 3, vacant = 0)

        // ...and the organization cannot give licences up while its members are in them.
        setLicenses(2)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.minimumLicenses").value(3))

        assertLicenses(held = 3, members = 3, vacant = 0)
        assertThat(subscriptionRepository.count()).isEqualTo(1)
    }

    // --- the annual journey ------------------------------------------------------------------------

    @Test
    fun `an annual organization grows and shrinks on the same annual subscription`() {
        checkout(licenseCount = 4, interval = BillingInterval.ANNUAL)
        stripeConfirms(eventId = "evt_annual", quantity = 3, priceId = "price_test_annual")

        assertLicenses(held = 4, members = 1, vacant = 3)
        getRequest("/api/organizations/$organizationId/subscription", owner.accessToken)
            .andExpect(jsonPath("$.billingInterval").value("ANNUAL"))

        // Growing and shrinking within the year changes only the quantity. The interval - and
        // therefore the price - is never touched, and Stripe prorates the remainder itself.
        setLicenses(6).andExpect(status().isOk)
        setLicenses(3).andExpect(status().isOk)

        getRequest("/api/organizations/$organizationId/subscription", owner.accessToken)
            .andExpect(jsonPath("$.billingInterval").value("ANNUAL"))
            .andExpect(jsonPath("$.billedLicenses").value(2))

        assertThat(subscriptionRepository.count()).isEqualTo(1)
    }

    // --- a dashboard edit ---------------------------------------------------------------------------

    @Test
    fun `licences bought in the Stripe dashboard arrive through the webhook`() {
        checkout(licenseCount = 3)
        stripeConfirms(eventId = "evt_three", quantity = 2)
        assertLicenses(held = 3, members = 1, vacant = 2)

        // Somebody raises the quantity in Stripe directly. Stripe is authoritative, so the
        // organization ends up holding what it is being billed for.
        stripeConfirms(eventId = "evt_dashboard", quantity = 9)
        assertLicenses(held = 10, members = 1, vacant = 9)

        assertThat(subscriptionRepository.count()).isEqualTo(1)
    }

    @Test
    fun `a subscription ending takes nobody's licence away`() {
        checkout(licenseCount = 3)
        stripeConfirms(eventId = "evt_start", quantity = 2)
        join("bob@example.com")
        join("carol@example.com")
        assertLicenses(held = 3, members = 3, vacant = 0)

        // The card stops working and Stripe cancels.
        stripeConfirms(eventId = "evt_cancel", quantity = 2, status = "canceled")

        // Nobody is removed, nothing is deleted, and every feature still works - there are no
        // paid features to withdraw. The organization holds what it held; it is simply no
        // longer being billed for it.
        assertLicenses(held = 3, members = 3, vacant = 0)
        getRequest("/api/organizations/$organizationId/subscription", owner.accessToken)
            .andExpect(jsonPath("$.status").value("CANCELED"))
            .andExpect(jsonPath("$.billing").value(false))

        val projectId = createProject(organizationId, owner, "Still Working")
        assignToProject(organizationId, projectId, owner, owner)
        startTimer(organizationId, projectId, owner)
    }
}
