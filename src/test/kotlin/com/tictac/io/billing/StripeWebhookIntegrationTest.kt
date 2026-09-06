package com.tictac.io.billing

import com.tictac.io.support.OrganizationApiTest
import com.tictac.io.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * The webhook: signature verification, organization resolution, state mapping, idempotency.
 *
 * **Nothing is mocked in this class.** The signature is computed the way Stripe computes it
 * and checked by the real [StripeGateway]; the payloads are the JSON Stripe actually sends;
 * the state lands in a real PostgreSQL container. That is deliberate - this endpoint is
 * public and writes billing state, so a test that stubbed the verification would be testing
 * nothing that matters.
 */
@DisplayName("Stripe webhook")
class StripeWebhookIntegrationTest : OrganizationApiTest() {

    private lateinit var owner: TestUser
    private var organizationId = UUID.randomUUID()
    private val customerId = "cus_test_acme"

    @BeforeEach
    fun createOrganizationWithCustomer() {
        owner = newUser("owner@example.com")
        organizationId = createOrganization(owner, "Acme")

        // Established at checkout in the real flow; the mapping is what webhooks read.
        billingCustomerRepository.saveAndFlush(
            BillingCustomer(organizationId, BillingProvider.STRIPE, customerId),
        )
    }

    private fun deliver(payload: String, signature: String?) =
        mockMvc.perform(
            post("/api/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .apply { signature?.let { header("Stripe-Signature", it) } },
        )

    private fun deliverSigned(payload: String) =
        deliver(payload, StripeWebhookSupport.signatureHeader(payload))

    private fun subscription() = subscriptionRepository.findByOrganizationId(organizationId)

    // --- signature verification -----------------------------------------------------------

    @Test
    fun `a webhook with no signature header is rejected`() {
        val payload = StripeWebhookSupport.subscriptionEvent(
            "evt_1", "customer.subscription.created", "sub_1", customerId,
        )

        deliver(payload, signature = null).andExpect(status().isBadRequest)

        assertThat(subscription()).isNull()
        assertThat(billingWebhookEventRepository.count()).isZero()
    }

    @Test
    fun `a webhook with a forged signature is rejected`() {
        val payload = StripeWebhookSupport.subscriptionEvent(
            "evt_1", "customer.subscription.created", "sub_1", customerId,
        )

        listOf(
            "t=1,v1=deadbeef",
            "nonsense",
            "",
            // Correctly formed, but signed with a secret we do not share with Stripe.
            StripeWebhookSupport.signatureHeader(payload, secret = "whsec_attacker_secret"),
        ).forEach { deliver(payload, it).andExpect(status().isBadRequest) }

        assertThat(subscription()).isNull()
        assertThat(billingWebhookEventRepository.count()).isZero()
    }

    @Test
    fun `a signature over a different payload does not authenticate this one`() {
        val real = StripeWebhookSupport.subscriptionEvent(
            "evt_1", "customer.subscription.created", "sub_1", customerId, quantity = 5,
        )
        // The attack the signature exists to stop: take a valid delivery, edit the body.
        val tampered = real.replace("\"quantity\":5", "\"quantity\":500")

        deliver(tampered, StripeWebhookSupport.signatureHeader(real)).andExpect(status().isBadRequest)

        assertThat(subscription()).isNull()
    }

    @Test
    fun `a replayed signature from long ago is rejected`() {
        val payload = StripeWebhookSupport.subscriptionEvent(
            "evt_1", "customer.subscription.created", "sub_1", customerId,
        )
        val stale = StripeWebhookSupport.signatureHeader(
            payload,
            timestampSeconds = Instant.now().minusSeconds(3600).epochSecond,
        )

        // The timestamp is inside the signed material and outside Stripe's tolerance, so a
        // captured request cannot be re-sent later.
        deliver(payload, stale).andExpect(status().isBadRequest)

        assertThat(subscription()).isNull()
    }

    @Test
    fun `a validly signed but malformed payload is rejected`() {
        listOf("not json at all", "{}", """{"id":"evt_1"}""").forEach { payload ->
            deliverSigned(payload).andExpect(status().is4xxClientError)
        }

        assertThat(subscription()).isNull()
    }

    // --- subscription lifecycle -------------------------------------------------------------

    @Test
    fun `a subscription-created event creates the subscription for the right organization`() {
        val payload = StripeWebhookSupport.subscriptionEvent(
            eventId = "evt_created",
            type = "customer.subscription.created",
            subscriptionId = "sub_test_1",
            customerId = customerId,
            status = "active",
            quantity = 5,
        )

        deliverSigned(payload).andExpect(status().isOk)

        val subscription = subscription()!!
        assertThat(subscription.organizationId).isEqualTo(organizationId)
        assertThat(subscription.plan).isEqualTo(SubscriptionPlan.PRO)
        assertThat(subscription.status).isEqualTo(SubscriptionStatus.ACTIVE)
        assertThat(subscription.seatQuantity).isEqualTo(5)
        assertThat(subscription.providerSubscriptionId).isEqualTo("sub_test_1")
        assertThat(subscription.provider).isEqualTo(BillingProvider.STRIPE)
        // Read off the subscription *item*, which is where the current API puts them.
        assertThat(subscription.currentPeriodStart).isEqualTo(Instant.ofEpochSecond(1_760_000_000))
        assertThat(subscription.currentPeriodEnd).isEqualTo(Instant.ofEpochSecond(1_762_678_400))
        assertThat(subscription.cancelAtPeriodEnd).isFalse()
        assertThat(subscription.cancelledAt).isNull()
    }

    @Test
    fun `a subscription-updated event updates the existing row rather than adding one`() {
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_created", "customer.subscription.created", "sub_test_1", customerId, quantity = 5,
            ),
        ).andExpect(status().isOk)
        val originalId = subscription()!!.id

        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_updated", "customer.subscription.updated", "sub_test_1", customerId,
                status = "past_due", quantity = 12,
            ),
        ).andExpect(status().isOk)

        val subscription = subscription()!!
        assertThat(subscription.id).isEqualTo(originalId)
        assertThat(subscription.status).isEqualTo(SubscriptionStatus.PAST_DUE)
        assertThat(subscription.seatQuantity).isEqualTo(12)
        assertThat(subscriptionRepository.count()).isEqualTo(1)
    }

    @Test
    fun `cancel-at-period-end is distinguishable from cancelled`() {
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_created", "customer.subscription.created", "sub_test_1", customerId,
            ),
        ).andExpect(status().isOk)

        // Stripe's representation: still `active`, still paid up, flagged not to renew.
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_cancelling", "customer.subscription.updated", "sub_test_1", customerId,
                status = "active", cancelAtPeriodEnd = true, canceledAt = 1_761_000_000,
            ),
        ).andExpect(status().isOk)

        val cancelling = subscription()!!
        assertThat(cancelling.status).isEqualTo(SubscriptionStatus.ACTIVE)
        assertThat(cancelling.cancelAtPeriodEnd).isTrue()
        assertThat(cancelling.cancelledAt).isEqualTo(Instant.ofEpochSecond(1_761_000_000))
        // Still entitled - they have paid until the period ends.
        assertThat(cancelling.status.grantsAccess()).isTrue()

        // ...and when the period actually ends, Stripe says so.
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_deleted", "customer.subscription.deleted", "sub_test_1", customerId,
                status = "canceled", cancelAtPeriodEnd = false, canceledAt = 1_761_000_000,
            ),
        ).andExpect(status().isOk)

        val cancelled = subscription()!!
        assertThat(cancelled.status).isEqualTo(SubscriptionStatus.CANCELED)
        assertThat(cancelled.status.grantsAccess()).isFalse()
        // The record survives cancellation; it is not deleted.
        assertThat(subscriptionRepository.count()).isEqualTo(1)
    }

    @Test
    fun `every Stripe status this backend accepts maps to a distinct local status`() {
        val mappings = mapOf(
            "trialing" to SubscriptionStatus.TRIALING,
            "active" to SubscriptionStatus.ACTIVE,
            "past_due" to SubscriptionStatus.PAST_DUE,
            "unpaid" to SubscriptionStatus.UNPAID,
            "paused" to SubscriptionStatus.PAUSED,
            "incomplete" to SubscriptionStatus.INCOMPLETE,
            "incomplete_expired" to SubscriptionStatus.INCOMPLETE_EXPIRED,
            "canceled" to SubscriptionStatus.CANCELED,
        )

        mappings.entries.forEachIndexed { index, (stripeStatus, expected) ->
            deliverSigned(
                StripeWebhookSupport.subscriptionEvent(
                    "evt_status_$index", "customer.subscription.updated", "sub_test_1",
                    customerId, status = stripeStatus,
                ),
            ).andExpect(status().isOk)

            assertThat(subscription()!!.status)
                .describedAs("stripe status %s", stripeStatus)
                .isEqualTo(expected)
        }
    }

    @Test
    fun `an unrecognised Stripe status fails loudly rather than guessing`() {
        // Guessing ACTIVE would hand out paid features on a status nobody has read; guessing
        // CANCELED would cut off a paying customer. Non-2xx makes Stripe retry and show it.
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_weird", "customer.subscription.created", "sub_test_1", customerId,
                status = "some_new_stripe_status",
            ),
        ).andExpect(status().is5xxServerError)

        assertThat(subscription()).isNull()
        // The event is not recorded either, so a corrected retry can still be applied.
        assertThat(billingWebhookEventRepository.count()).isZero()
    }

    @Test
    fun `a price this deployment does not know fails loudly`() {
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_price", "customer.subscription.created", "sub_test_1", customerId,
                priceId = "price_never_configured",
            ),
        ).andExpect(status().is5xxServerError)

        assertThat(subscription()).isNull()
    }

    @Test
    fun `a subscription with no line items fails rather than writing a guessed row`() {
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_empty", "customer.subscription.created", "sub_test_1", customerId,
                includeItem = false,
            ),
        ).andExpect(status().is5xxServerError)

        assertThat(subscription()).isNull()
    }

    // --- organization resolution ---------------------------------------------------------------

    @Test
    fun `an event for a Stripe customer we do not know is ignored safely`() {
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_unknown", "customer.subscription.created", "sub_test_1", "cus_never_seen",
            ),
        ).andExpect(status().isOk)

        assertThat(subscriptionRepository.count()).isZero()
        // Recorded, so Stripe stops retrying something we will never act on.
        assertThat(billingWebhookEventRepository.count()).isEqualTo(1)
    }

    @Test
    fun `an unhandled event type is recorded and ignored`() {
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_invoice", "invoice.payment_succeeded", "sub_test_1", customerId,
            ),
        ).andExpect(status().isOk)

        assertThat(subscriptionRepository.count()).isZero()
        assertThat(billingWebhookEventRepository.count()).isEqualTo(1)
    }

    // --- idempotency -----------------------------------------------------------------------------

    @Test
    fun `the same event delivered twice changes state once`() {
        val payload = StripeWebhookSupport.subscriptionEvent(
            "evt_once", "customer.subscription.created", "sub_test_1", customerId, quantity = 5,
        )

        deliverSigned(payload).andExpect(status().isOk)
        val afterFirst = subscription()!!
        val id = afterFirst.id
        val updatedAt = afterFirst.updatedAt

        // Byte-identical redelivery, exactly as Stripe retries.
        deliverSigned(payload).andExpect(status().isOk)

        val afterSecond = subscription()!!
        assertThat(afterSecond.id).isEqualTo(id)
        assertThat(afterSecond.updatedAt).isEqualTo(updatedAt)
        assertThat(afterSecond.seatQuantity).isEqualTo(5)
        assertThat(subscriptionRepository.count()).isEqualTo(1)
        assertThat(billingWebhookEventRepository.count()).isEqualTo(1)
    }

    @Test
    fun `a redelivered event cannot undo a newer one`() {
        val created = StripeWebhookSupport.subscriptionEvent(
            "evt_created", "customer.subscription.created", "sub_test_1", customerId, quantity = 5,
        )

        deliverSigned(created).andExpect(status().isOk)
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_updated", "customer.subscription.updated", "sub_test_1", customerId, quantity = 20,
            ),
        ).andExpect(status().isOk)

        // Stripe retries the *older* event after the newer one has landed. Without
        // idempotency this would roll the seat count back to 5.
        deliverSigned(created).andExpect(status().isOk)

        assertThat(subscription()!!.seatQuantity).isEqualTo(20)
    }

    @Test
    fun `two different events both apply`() {
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_a", "customer.subscription.created", "sub_test_1", customerId, quantity = 5,
            ),
        ).andExpect(status().isOk)
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_b", "customer.subscription.updated", "sub_test_1", customerId, quantity = 9,
            ),
        ).andExpect(status().isOk)

        assertThat(subscription()!!.seatQuantity).isEqualTo(9)
        assertThat(billingWebhookEventRepository.count()).isEqualTo(2)
    }

    @Test
    fun `the database refuses a duplicate event id even with the service out of the way`() {
        deliverSigned(
            StripeWebhookSupport.subscriptionEvent(
                "evt_once", "customer.subscription.created", "sub_test_1", customerId,
            ),
        ).andExpect(status().isOk)

        org.assertj.core.api.Assertions.assertThatThrownBy {
            billingWebhookEventRepository.saveAndFlush(
                BillingWebhookEvent(BillingProvider.STRIPE, "evt_once", "customer.subscription.created"),
            )
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }
}
