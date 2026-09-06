package com.tictac.io.billing

import com.stripe.StripeClient
import com.stripe.exception.SignatureVerificationException
import com.stripe.exception.StripeException
import com.stripe.model.Event
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.billingportal.SessionCreateParams as PortalSessionCreateParams
import com.stripe.param.checkout.SessionCreateParams
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/** Billing is not configured in this deployment. */
class BillingNotConfiguredException :
    RuntimeException("Billing is not configured for this deployment")

/** Stripe was reachable but refused, or could not be reached at all. */
class StripeUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** A webhook request whose signature does not verify against the configured secret. */
class InvalidWebhookSignatureException(message: String) : RuntimeException(message)

/**
 * A webhook whose signature verified but whose body is not a usable event.
 *
 * Distinct from a signature failure on purpose. Only someone holding the signing secret can
 * produce one, so it is not an attack - it is a bug, or a version of the API sending
 * something unrecognisable, and it should read as a bad request rather than a forgery.
 */
class InvalidWebhookPayloadException(message: String) : RuntimeException(message)

/** A Stripe checkout or portal session, reduced to what a client actually needs. */
data class StripeSessionHandle(val id: String, val url: String)

/**
 * Every call that leaves this process for Stripe, and nothing else.
 *
 * A deliberate seam. Isolating the network here means the whole of the interesting logic -
 * resolving organizations, mapping states, idempotency, authorisation - is testable against a
 * real database without a Stripe account, and the tests that *do* need to stand in for Stripe
 * replace exactly one bean rather than reaching into a service.
 *
 * A concrete class rather than an interface: there is one implementation, tests override the
 * bean, and an interface with a single implementation is machinery this codebase avoids.
 *
 * Note what is *not* here: webhook signature verification is, but nothing in this class
 * decides anything. It converts Stripe's types into ours and throws when Stripe is unusable.
 */
@Component
class StripeGateway(
    private val properties: BillingProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Built lazily so a deployment with no Stripe key still starts - the client is only
     * constructed the first time billing is actually used, which by then has already been
     * refused if it is not configured.
     */
    private val client: StripeClient by lazy { StripeClient(properties.secretKey) }

    /**
     * Creates the Stripe customer for an organization.
     *
     * The organization id goes into metadata for a human reading the Stripe dashboard, and
     * for nothing else - see [StripeWebhookService] on why metadata is never an authorisation
     * input on the way back.
     */
    fun createCustomer(organizationId: UUID, organizationName: String): String {
        requireConfigured()

        return stripeCall("create customer") {
            client.customers().create(
                CustomerCreateParams.builder()
                    .setName(organizationName)
                    .putMetadata(ORGANIZATION_METADATA_KEY, organizationId.toString())
                    .build(),
            ).id
        }
    }

    /**
     * A subscription-mode Checkout Session for an existing customer.
     *
     * Bound to the customer we already created, so the subscription Stripe eventually reports
     * arrives attached to a customer this backend can map back to an organization.
     */
    fun createCheckoutSession(
        customerId: String,
        priceId: String,
        organizationId: UUID,
        seatQuantity: Long,
    ): StripeSessionHandle {
        requireConfigured()

        return stripeCall("create checkout session") {
            val session = client.checkout().sessions().create(
                SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(customerId)
                    .setSuccessUrl(properties.successUrl)
                    .setCancelUrl(properties.cancelUrl)
                    .addLineItem(
                        SessionCreateParams.LineItem.builder()
                            .setPrice(priceId)
                            .setQuantity(seatQuantity)
                            .build(),
                    )
                    .putMetadata(ORGANIZATION_METADATA_KEY, organizationId.toString())
                    .build(),
            )

            StripeSessionHandle(session.id, session.url)
        }
    }

    /** A Billing Portal session, where Stripe handles cards, invoices and cancellation. */
    fun createPortalSession(customerId: String): StripeSessionHandle {
        requireConfigured()

        return stripeCall("create billing portal session") {
            val session = client.billingPortal().sessions().create(
                PortalSessionCreateParams.builder()
                    .setCustomer(customerId)
                    .setReturnUrl(properties.portalReturnUrl)
                    .build(),
            )

            StripeSessionHandle(session.id, session.url)
        }
    }

    /**
     * Verifies a webhook's signature and parses it, or refuses.
     *
     * This is the entire authentication of the webhook endpoint. Stripe signs the raw body
     * with the endpoint's signing secret; `constructEvent` recomputes the HMAC over the exact
     * bytes and also enforces a timestamp tolerance, which is what stops a captured payload
     * being replayed later. The raw string must be the untouched body - re-serialising parsed
     * JSON would change the bytes and every signature would fail.
     */
    fun verifyAndParse(payload: String, signatureHeader: String?): Event {
        if (properties.webhookSecret.isBlank()) {
            throw BillingNotConfiguredException()
        }
        if (signatureHeader.isNullOrBlank()) {
            throw InvalidWebhookSignatureException("Missing Stripe-Signature header")
        }

        val event = try {
            Webhook.constructEvent(payload, signatureHeader, properties.webhookSecret)
        } catch (ex: SignatureVerificationException) {
            // Deliberately terse, and deliberately does not echo the payload or the header.
            throw InvalidWebhookSignatureException("Stripe signature verification failed")
        } catch (ex: Exception) {
            throw InvalidWebhookSignatureException("Webhook payload could not be parsed")
        }

        // Structurally checked before anything downstream relies on it. Both fields are
        // NOT NULL where the event is recorded, and an event with neither an id nor a type
        // cannot be made idempotent or dispatched - so it is refused here, as a bad request,
        // rather than becoming a constraint violation three layers down.
        if (event.id.isNullOrBlank() || event.type.isNullOrBlank()) {
            throw InvalidWebhookPayloadException("Webhook payload is missing an event id or type")
        }

        return event
    }

    private fun requireConfigured() {
        if (!properties.configured) throw BillingNotConfiguredException()
    }

    /**
     * One place where a Stripe failure becomes one of ours.
     *
     * The message is logged, not returned: Stripe's errors can name customers, prices and
     * internal ids, and none of that belongs in a response to a browser.
     */
    private fun <T> stripeCall(what: String, call: () -> T): T =
        try {
            call()
        } catch (ex: StripeException) {
            log.error("Stripe call failed: {}", what, ex)
            throw StripeUnavailableException("Could not $what", ex)
        }

    companion object {
        /** Human-facing breadcrumb in the Stripe dashboard. Never trusted on the way back. */
        const val ORGANIZATION_METADATA_KEY = "tictac_organization_id"
    }
}
