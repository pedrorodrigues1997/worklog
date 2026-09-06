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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Checkout, the billing portal, and reading subscription state.
 *
 * [StripeGateway] is replaced here - it is the one component that makes a network call, and
 * a test suite that needed a Stripe account to run would not be run. Everything on this side
 * of it is real: authorisation, the customer row, the transaction, the database.
 *
 * The webhook path is tested separately and mocks nothing, because that is where the
 * security boundary actually is.
 */
@DisplayName("Billing API: checkout, portal and subscription state")
class BillingApiIntegrationTest : OrganizationApiTest() {

    @MockitoBean
    private lateinit var stripeGateway: StripeGateway

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

        whenever(stripeGateway.createCustomer(any(), any())).thenReturn("cus_test_new")
        whenever(stripeGateway.createCheckoutSession(any(), any(), any(), any()))
            .thenReturn(StripeSessionHandle("cs_test_1", "https://checkout.stripe.test/cs_test_1"))
        whenever(stripeGateway.createPortalSession(any()))
            .thenReturn(StripeSessionHandle("bps_test_1", "https://portal.stripe.test/bps_test_1"))
    }

    private fun checkoutPath(organization: UUID = organizationId) =
        "/api/organizations/$organization/billing/checkout"

    private fun portalPath(organization: UUID = organizationId) =
        "/api/organizations/$organization/billing/portal"

    private fun subscriptionPath(organization: UUID = organizationId) =
        "/api/organizations/$organization/subscription"

    private fun checkout(caller: TestUser, organization: UUID = organizationId, body: String = """{"plan":"PRO"}""") =
        postJson(checkoutPath(organization), body, caller.accessToken)

    // --- who may change billing -------------------------------------------------------------

    @Test
    fun `an owner can start checkout`() {
        checkout(owner)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sessionId").value("cs_test_1"))
            .andExpect(jsonPath("$.url").value("https://checkout.stripe.test/cs_test_1"))
    }

    @Test
    fun `an admin cannot start checkout, and neither can a member`() {
        // Billing authority is narrower than administrative authority: an ADMIN runs the
        // company's work, an OWNER commits it to spending money.
        checkout(admin).andExpect(status().isForbidden)
        checkout(member).andExpect(status().isForbidden)

        verify(stripeGateway, never()).createCheckoutSession(any(), any(), any(), any())
        assertThat(billingCustomerRepository.count()).isZero()
    }

    @Test
    fun `an unauthenticated request cannot start checkout`() {
        postJson(checkoutPath(), """{"plan":"PRO"}""").andExpect(status().isUnauthorized)

        verify(stripeGateway, never()).createCustomer(any(), any())
    }

    @Test
    fun `a non-member cannot start checkout, and is not told the organization exists`() {
        checkout(newUser("outsider@example.com")).andExpect(status().isNotFound)

        verify(stripeGateway, never()).createCustomer(any(), any())
    }

    @Test
    fun `only the owner can open the billing portal`() {
        postJson(portalPath(), "", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value("https://portal.stripe.test/bps_test_1"))

        postJson(portalPath(), "", admin.accessToken).andExpect(status().isForbidden)
        postJson(portalPath(), "", member.accessToken).andExpect(status().isForbidden)
        postJson(portalPath(), "").andExpect(status().isUnauthorized)
    }

    // --- the Stripe customer -----------------------------------------------------------------

    @Test
    fun `the organization gets exactly one Stripe customer, reused across sessions`() {
        checkout(owner).andExpect(status().isOk)
        checkout(owner).andExpect(status().isOk)
        postJson(portalPath(), "", owner.accessToken).andExpect(status().isOk)

        // Created once; every later session reuses it. Minting one per checkout would
        // scatter a single company's billing history across duplicate Stripe customers.
        verify(stripeGateway, times(1)).createCustomer(eq(organizationId), eq("Acme"))
        assertThat(billingCustomerRepository.count()).isEqualTo(1)

        val customer = billingCustomerRepository.findAll().single()
        assertThat(customer.organizationId).isEqualTo(organizationId)
        assertThat(customer.provider).isEqualTo(BillingProvider.STRIPE)
        assertThat(customer.providerCustomerId).isEqualTo("cus_test_new")
    }

    @Test
    fun `each organization gets its own customer`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")
        whenever(stripeGateway.createCustomer(eq(otherOrganization), any())).thenReturn("cus_test_other")

        checkout(owner).andExpect(status().isOk)
        checkout(otherOwner, organization = otherOrganization).andExpect(status().isOk)

        assertThat(billingCustomerRepository.count()).isEqualTo(2)
        assertThat(
            billingCustomerRepository
                .findByOrganizationIdAndProvider(otherOrganization, BillingProvider.STRIPE)!!.providerCustomerId,
        ).isEqualTo("cus_test_other")
    }

    // --- what checkout does and does not do ----------------------------------------------------

    @Test
    fun `checkout uses the configured price and the caller's own organization`() {
        checkout(owner, body = """{"plan":"PRO","seatQuantity":7}""").andExpect(status().isOk)

        // The price comes from configuration, never from the request - a client that could
        // name a price could name a cheaper one.
        verify(stripeGateway).createCheckoutSession(
            eq("cus_test_new"),
            eq("price_test_pro"),
            eq(organizationId),
            eq(7L),
        )
    }

    @Test
    fun `creating a checkout session does not make the organization subscribed`() {
        checkout(owner).andExpect(status().isOk)

        // The whole point of the webhook. A prepared payment form is not a payment, and a
        // client that never returns from Stripe must not leave anything activated here.
        assertThat(subscriptionRepository.count()).isZero()
        getRequest(subscriptionPath(), owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plan").value("FREE"))
            .andExpect(jsonPath("$.seatQuantity").value(0))
    }

    @Test
    fun `a plan with no configured price cannot be checked out`() {
        checkout(owner, body = """{"plan":"FREE"}""")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.title").value("Plan is not purchasable"))

        verify(stripeGateway, never()).createCheckoutSession(any(), any(), any(), any())
    }

    @Test
    fun `checkout validates its request`() {
        listOf(
            """{}""",
            """{"plan":null}""",
            """{"plan":"NOT_A_PLAN"}""",
            """{"plan":"PRO","seatQuantity":0}""",
            """{"plan":"PRO","seatQuantity":100000}""",
        ).forEach { checkout(owner, body = it).andExpect(status().isBadRequest) }

        verify(stripeGateway, never()).createCheckoutSession(any(), any(), any(), any())
    }

    @Test
    fun `a Stripe failure surfaces as a gateway error, not a 500`() {
        whenever(stripeGateway.createCustomer(any(), any()))
            .thenThrow(StripeUnavailableException("Could not create customer"))

        checkout(owner)
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.title").value("Billing provider error"))

        // ...and nothing local was left half-written.
        assertThat(billingCustomerRepository.count()).isZero()
    }

    // --- reading the subscription ----------------------------------------------------------------

    @Test
    fun `an organization that has never subscribed reads as FREE rather than 404`() {
        getRequest(subscriptionPath(), owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
            .andExpect(jsonPath("$.plan").value("FREE"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false))
    }

    @Test
    fun `every member can read the subscription`() {
        subscriptionRepository.saveAndFlush(
            Subscription(organizationId, SubscriptionPlan.PRO, SubscriptionStatus.ACTIVE, 5, BillingProvider.STRIPE),
        )

        listOf(owner, admin, member).forEach { user ->
            getRequest(subscriptionPath(), user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.seatQuantity").value(5))
        }
    }

    @Test
    fun `the subscription response exposes no Stripe identifiers or secrets`() {
        billingCustomerRepository.saveAndFlush(
            BillingCustomer(organizationId, BillingProvider.STRIPE, "cus_secret_identifier"),
        )
        subscriptionRepository.saveAndFlush(
            Subscription(organizationId, SubscriptionPlan.PRO, SubscriptionStatus.ACTIVE, 5, BillingProvider.STRIPE)
                .apply { providerSubscriptionId = "sub_secret_identifier" },
        )

        val body = getRequest(subscriptionPath(), member.accessToken)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("cus_secret_identifier")
        assertThat(body).doesNotContain("sub_secret_identifier")
        assertThat(body).doesNotContain("sk_test")
        assertThat(body).doesNotContain("whsec")
        assertThat(body).doesNotContain("price_test_pro")
    }

    @Test
    fun `seat quantity is what was purchased, not the member count`() {
        // Three members, five seats. Allowed at the data-model level: seat enforcement is a
        // separate decision and inventing it here would be a pricing rule set by a default.
        subscriptionRepository.saveAndFlush(
            Subscription(organizationId, SubscriptionPlan.PRO, SubscriptionStatus.ACTIVE, 5, BillingProvider.STRIPE),
        )

        assertThat(organizationMemberRepository.count()).isEqualTo(3)
        getRequest(subscriptionPath(), owner.accessToken)
            .andExpect(jsonPath("$.seatQuantity").value(5))
    }

    // --- tenant isolation ---------------------------------------------------------------------------

    @Test
    fun `an owner of one organization cannot touch another's billing`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")
        subscriptionRepository.saveAndFlush(
            Subscription(
                otherOrganization, SubscriptionPlan.PRO, SubscriptionStatus.ACTIVE, 42, BillingProvider.STRIPE,
            ),
        )

        checkout(owner, organization = otherOrganization).andExpect(status().isNotFound)
        postJson(portalPath(otherOrganization), "", owner.accessToken).andExpect(status().isNotFound)

        val body = getRequest(subscriptionPath(otherOrganization), owner.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString
        assertThat(body).doesNotContain("42")

        verify(stripeGateway, never()).createCustomer(any(), any())
    }

    @Test
    fun `each organization reads only its own subscription`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")

        subscriptionRepository.saveAndFlush(
            Subscription(organizationId, SubscriptionPlan.PRO, SubscriptionStatus.ACTIVE, 5, BillingProvider.STRIPE),
        )
        subscriptionRepository.saveAndFlush(
            Subscription(
                otherOrganization, SubscriptionPlan.FREE, SubscriptionStatus.CANCELED, 99, BillingProvider.STRIPE,
            ),
        )

        getRequest(subscriptionPath(), owner.accessToken)
            .andExpect(jsonPath("$.seatQuantity").value(5))
            .andExpect(jsonPath("$.plan").value("PRO"))
        getRequest(subscriptionPath(otherOrganization), otherOwner.accessToken)
            .andExpect(jsonPath("$.seatQuantity").value(99))
            .andExpect(jsonPath("$.plan").value("FREE"))
    }

    @Test
    fun `reading a subscription requires authentication`() {
        getRequest(subscriptionPath()).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the database refuses a second subscription for one organization`() {
        subscriptionRepository.saveAndFlush(
            Subscription(organizationId, SubscriptionPlan.PRO, SubscriptionStatus.ACTIVE, 5, BillingProvider.STRIPE),
        )

        // The final guarantee behind every webhook path: one subscription per organization,
        // whatever the application does.
        org.assertj.core.api.Assertions.assertThatThrownBy {
            subscriptionRepository.saveAndFlush(
                Subscription(
                    organizationId, SubscriptionPlan.PRO, SubscriptionStatus.ACTIVE, 9, BillingProvider.STRIPE,
                ),
            )
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }
}
