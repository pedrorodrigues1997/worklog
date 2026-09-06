package com.tictac.io.billing

import com.tictac.io.organization.OrganizationRole
import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Checkout, the billing portal, and reading the subscription.
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
        // Three members seeded directly, so the organization holds three licences to match.
        setLicenseCount(organizationId, 3)

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

    /** The default buys the three licences this three-person organization already occupies. */
    private fun checkout(
        caller: TestUser,
        organization: UUID = organizationId,
        body: String = """{"licenses":3,"interval":"MONTHLY"}""",
    ) = postJson(checkoutPath(organization), body, caller.accessToken)

    // --- who may buy licences ---------------------------------------------------------------

    @Test
    fun `an owner can check out for the licences they ask for`() {
        checkout(owner, body = """{"licenses":10,"interval":"MONTHLY"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sessionId").value("cs_test_1"))
            .andExpect(jsonPath("$.url").value("https://checkout.stripe.test/cs_test_1"))
            .andExpect(jsonPath("$.licenseCount").value(10))
            // One fewer, because the first licence is included free.
            .andExpect(jsonPath("$.paidLicenses").value(9))
            .andExpect(jsonPath("$.interval").value("MONTHLY"))

        verify(stripeGateway).createCheckoutSession(any(), any(), eq(organizationId), eq(9L))
    }

    @Test
    fun `an admin can check out, because inviting already acquires licences`() {
        checkout(admin).andExpect(status().isOk)

        verify(stripeGateway).createCheckoutSession(any(), any(), eq(organizationId), eq(2L))
    }

    @Test
    fun `a plain member cannot check out`() {
        checkout(member).andExpect(status().isForbidden)

        verify(stripeGateway, never()).createCheckoutSession(any(), any(), any(), any())
        assertThat(billingCustomerRepository.count()).isZero()
    }

    @Test
    fun `an unauthenticated request cannot check out`() {
        postJson(checkoutPath(), """{"licenses":3,"interval":"MONTHLY"}""").andExpect(status().isUnauthorized)

        verify(stripeGateway, never()).createCustomer(any(), any())
    }

    @Test
    fun `a non-member cannot check out, and is not told the organization exists`() {
        checkout(newUser("outsider@example.com")).andExpect(status().isNotFound)

        verify(stripeGateway, never()).createCustomer(any(), any())
    }

    @Test
    fun `only the owner can open the billing portal`() {
        postJson(portalPath(), "", owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value("https://portal.stripe.test/bps_test_1"))

        // Cards, invoices and cancellation are the payment instrument itself, not the
        // headcount - so this stays narrower than licence management.
        postJson(portalPath(), "", admin.accessToken).andExpect(status().isForbidden)
        postJson(portalPath(), "", member.accessToken).andExpect(status().isForbidden)
        postJson(portalPath(), "").andExpect(status().isUnauthorized)
    }

    // --- monthly and annual --------------------------------------------------------------------

    @Test
    fun `an organization can buy monthly billing`() {
        checkout(owner, body = """{"licenses":5,"interval":"MONTHLY"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.interval").value("MONTHLY"))

        verify(stripeGateway).createCheckoutSession(any(), eq("price_test_monthly"), any(), eq(4L))
    }

    @Test
    fun `an organization can buy annual billing`() {
        checkout(owner, body = """{"licenses":5,"interval":"ANNUAL"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.interval").value("ANNUAL"))

        // A different price, and that is the *only* difference - the quantity means the same
        // thing either way.
        verify(stripeGateway).createCheckoutSession(any(), eq("price_test_annual"), any(), eq(4L))
    }

    @Test
    fun `changing licences on an annual subscription keeps it annual`() {
        subscribeOrganization(organizationId, licenses = 5, interval = BillingInterval.ANNUAL)

        putJson("/api/organizations/$organizationId/licenses", """{"licenses":8}""", owner.accessToken)
            .andExpect(status().isOk)

        // Only the quantity is sent. The price - and therefore the interval - is untouched,
        // and Stripe prorates the remainder of the year itself.
        verify(stripeGateway).updateSubscriptionQuantity(any(), any(), eq(7L))
        verify(stripeGateway, never()).createCheckoutSession(any(), any(), any(), any())

        getRequest(subscriptionPath(), owner.accessToken)
            .andExpect(jsonPath("$.billingInterval").value("ANNUAL"))
            .andExpect(jsonPath("$.billedLicenses").value(7))
    }

    @Test
    fun `an unknown interval is rejected before anything reaches Stripe`() {
        checkout(owner, body = """{"licenses":3,"interval":"WEEKLY"}""").andExpect(status().isBadRequest)
        checkout(owner, body = """{"licenses":3}""").andExpect(status().isBadRequest)

        verify(stripeGateway, never()).createCheckoutSession(any(), any(), any(), any())
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
        checkout(otherOwner, organization = otherOrganization, body = """{"licenses":2,"interval":"MONTHLY"}""")
            .andExpect(status().isOk)

        assertThat(billingCustomerRepository.count()).isEqualTo(2)
        assertThat(
            billingCustomerRepository
                .findByOrganizationIdAndProvider(otherOrganization, BillingProvider.STRIPE)!!.providerCustomerId,
        ).isEqualTo("cus_test_other")
    }

    // --- what checkout does and does not do ----------------------------------------------------

    @Test
    fun `checkout uses the configured price, which the client cannot influence`() {
        // The licence count and the interval are the customer's to choose; the price is not,
        // and naming one in the body changes nothing because it is not a field here.
        checkout(owner, body = """{"licenses":3,"interval":"MONTHLY","priceId":"price_free","amount":0}""")
            .andExpect(status().isOk)

        verify(stripeGateway).createCheckoutSession(
            eq("cus_test_new"),
            eq("price_test_monthly"),
            eq(organizationId),
            eq(2L),
        )
    }

    @Test
    fun `checkout cannot buy fewer licences than the organization's members occupy`() {
        // Three members occupy three licences. Buying two would put the organization over
        // capacity the moment it started paying.
        checkout(owner, body = """{"licenses":2,"interval":"MONTHLY"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Licences are occupied"))
            .andExpect(jsonPath("$.minimumLicenses").value(3))

        verify(stripeGateway, never()).createCheckoutSession(any(), any(), any(), any())
    }

    @Test
    fun `checkout must buy at least a second licence, since the first is free`() {
        val soloOwner = newUser("solo@example.com")
        val solo = createOrganization(soloOwner, "Solo")

        // A one-licence organization is already free; there is nothing to check out for. And
        // giving licences up is a licence change, not a checkout.
        postJson(
            "/api/organizations/$solo/billing/checkout",
            """{"licenses":1,"interval":"MONTHLY"}""",
            soloOwner.accessToken,
        ).andExpect(status().isBadRequest)

        verify(stripeGateway, never()).createCheckoutSession(any(), any(), any(), any())
    }

    @Test
    fun `an organization that already has a subscription cannot check out again`() {
        subscribeOrganization(organizationId, licenses = 3)

        checkout(owner)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Already subscribed"))

        // The whole point of the model: one subscription, its quantity changed - never a second.
        verify(stripeGateway, never()).createCheckoutSession(any(), any(), any(), any())
        assertThat(subscriptionRepository.count()).isEqualTo(1)
    }

    @Test
    fun `a cancelled organization can check out again`() {
        subscribeOrganization(organizationId, licenses = 3, status = SubscriptionStatus.CANCELED)

        // A cancelled subscription cannot have licences added to it - there is nothing live -
        // so checkout is the way back, and it is not refused as a duplicate.
        checkout(owner).andExpect(status().isOk)
    }

    @Test
    fun `creating a checkout session grants no licences`() {
        checkout(owner, body = """{"licenses":9,"interval":"MONTHLY"}""").andExpect(status().isOk)

        // The whole point of the webhook. A prepared payment form is not a payment, and a
        // client that never returns from Stripe must not come away holding nine licences.
        assertThat(subscriptionRepository.count()).isZero()
        assertThat(licenseCountOf(organizationId)).isEqualTo(3)
        getRequest(subscriptionPath(), owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.billedLicenses").value(0))
            .andExpect(jsonPath("$.billing").value(false))
    }

    @Test
    fun `the licence count and interval are required and bounded`() {
        // 400 rather than 422: a missing or malformed field is a broken request, not a domain
        // decision the organization is not allowed to make.
        listOf(
            "",
            """{}""",
            """{"licenses":null,"interval":"MONTHLY"}""",
            """{"licenses":-1,"interval":"MONTHLY"}""",
            """{"licenses":100000,"interval":"MONTHLY"}""",
            """{"licenses":5,"interval":null}""",
        ).forEach { body ->
            postJson(checkoutPath(), body, owner.accessToken).andExpect(status().isBadRequest)
        }

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
    fun `an organization that has never subscribed reads as unsubscribed rather than 404`() {
        getRequest(subscriptionPath(), owner.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.billing").value(false))
            .andExpect(jsonPath("$.billingInterval").doesNotExist())
            .andExpect(jsonPath("$.billedLicenses").value(0))
            .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false))
            // It still holds licences, whether or not Stripe has ever heard of it.
            .andExpect(jsonPath("$.licenseCount").value(3))
    }

    @Test
    fun `every member can read the subscription`() {
        subscribeOrganization(organizationId, licenses = 6)

        listOf(owner, admin, member).forEach { user ->
            getRequest(subscriptionPath(), user.accessToken)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.billing").value(true))
                .andExpect(jsonPath("$.billingInterval").value("MONTHLY"))
                .andExpect(jsonPath("$.billedLicenses").value(5))
                .andExpect(jsonPath("$.licenseCount").value(6))
        }
    }

    @Test
    fun `the subscription response has no plan, because there are no tiers`() {
        subscribeOrganization(organizationId, licenses = 4)

        val body = getRequest(subscriptionPath(), member.accessToken)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        // Every organization has every feature; a plan field would imply otherwise.
        assertThat(body).doesNotContain("\"plan\"")
        assertThat(body).doesNotContain("PRO")
        assertThat(body).doesNotContain("FREE")
    }

    @Test
    fun `the subscription response exposes no Stripe identifiers or secrets`() {
        billingCustomerRepository.saveAndFlush(
            BillingCustomer(organizationId, BillingProvider.STRIPE, "cus_secret_identifier"),
        )
        subscriptionRepository.saveAndFlush(
            Subscription(organizationId, BillingInterval.MONTHLY, SubscriptionStatus.ACTIVE, 5, BillingProvider.STRIPE)
                .apply { providerSubscriptionId = "sub_secret_identifier" },
        )

        val body = getRequest(subscriptionPath(), member.accessToken)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("cus_secret_identifier")
        assertThat(body).doesNotContain("sub_secret_identifier")
        assertThat(body).doesNotContain("sk_test")
        assertThat(body).doesNotContain("whsec")
        assertThat(body).doesNotContain("price_test")
    }

    @Test
    fun `reading a subscription requires authentication`() {
        getRequest(subscriptionPath()).andExpect(status().isUnauthorized)
    }

    // --- tenant isolation ---------------------------------------------------------------------------

    @Test
    fun `an owner of one organization cannot touch another's billing`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")
        subscriptionRepository.saveAndFlush(
            Subscription(
                otherOrganization, BillingInterval.MONTHLY, SubscriptionStatus.ACTIVE, 42, BillingProvider.STRIPE,
            ),
        )

        checkout(owner, organization = otherOrganization).andExpect(status().isNotFound)
        postJson(portalPath(otherOrganization), "", owner.accessToken).andExpect(status().isNotFound)

        val body = getRequest(subscriptionPath(otherOrganization), owner.accessToken)
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString
        // By field, not by value: the organization id is in the problem detail, and a random
        // UUID containing the digits of a quantity would make a substring check flaky.
        assertThat(body).doesNotContain("billedLicenses")
        assertThat(body).doesNotContain("licenseCount")

        verify(stripeGateway, never()).createCustomer(any(), any())
    }

    @Test
    fun `each organization reads only its own subscription`() {
        val otherOwner = newUser("other-owner@example.com")
        val otherOrganization = createOrganization(otherOwner, "Other Company")

        subscribeOrganization(organizationId, licenses = 6)
        subscribeOrganization(otherOrganization, licenses = 100, interval = BillingInterval.ANNUAL)

        getRequest(subscriptionPath(), owner.accessToken)
            .andExpect(jsonPath("$.billedLicenses").value(5))
            .andExpect(jsonPath("$.billingInterval").value("MONTHLY"))
        getRequest(subscriptionPath(otherOrganization), otherOwner.accessToken)
            .andExpect(jsonPath("$.billedLicenses").value(99))
            .andExpect(jsonPath("$.billingInterval").value("ANNUAL"))
    }

    @Test
    fun `the database refuses a second subscription for one organization`() {
        subscriptionRepository.saveAndFlush(
            Subscription(organizationId, BillingInterval.MONTHLY, SubscriptionStatus.ACTIVE, 5, BillingProvider.STRIPE),
        )

        // The final guarantee behind every webhook path: one subscription per organization,
        // whatever the application does.
        assertThatThrownBy {
            subscriptionRepository.saveAndFlush(
                Subscription(
                    organizationId, BillingInterval.ANNUAL, SubscriptionStatus.ACTIVE, 9, BillingProvider.STRIPE,
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
