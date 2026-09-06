package com.tictac.io.billing

import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Registers [BillingProperties], following the per-feature pattern the other configuration
 * classes use - the application class has no `@ConfigurationPropertiesScan`.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingProperties::class)
class BillingConfig

/**
 * Billing, under the organization being billed - so the tenant is in the path and goes
 * through the same [com.tictac.io.organization.OrganizationAccess] gate as everything else.
 *
 * No authorisation logic here; the service names the roles it requires.
 */
@RestController
@RequestMapping("/api/organizations/{organizationId}/billing")
class BillingController(
    private val billingService: BillingService,
) {

    /** OWNER only. Returns where to send the browser; activates nothing. */
    @PostMapping("/checkout")
    fun startCheckout(
        @PathVariable organizationId: UUID,
        @Valid @RequestBody request: StartCheckoutRequest,
    ): CheckoutSessionResponse = billingService.startCheckout(organizationId, request)

    /**
     * OWNER only. Stripe's own portal handles payment methods, invoices, and cancellation -
     * none of which this backend implements or wants to.
     */
    @PostMapping("/portal")
    fun openPortal(@PathVariable organizationId: UUID): BillingPortalSessionResponse =
        billingService.openBillingPortal(organizationId)
}

/**
 * The organization's subscription. Readable by any member; see [BillingService.getSubscription].
 *
 * Separate from [BillingController] because it is a different kind of thing: a read of
 * application state, at the path a client would expect, rather than an operation against
 * Stripe.
 */
@RestController
@RequestMapping("/api/organizations/{organizationId}/subscription")
class SubscriptionController(
    private val billingService: BillingService,
) {

    @GetMapping
    fun get(@PathVariable organizationId: UUID): SubscriptionResponse =
        billingService.getSubscription(organizationId)
}

/**
 * Stripe's server-to-server callback, and the only endpoint in the application authenticated
 * by something other than a JWT.
 *
 * It has to be public - Stripe holds no access token and never will - so its entire security
 * is the signature over the raw request body. That is why the payload is taken as a [String]
 * rather than a parsed object: the HMAC covers the exact bytes Stripe sent, and letting
 * Jackson parse and re-serialise them would change the bytes and fail every signature.
 *
 * Responses are shaped for Stripe rather than for a person. A 2xx means "stop sending this",
 * so an event that was handled *and* an event that will never be handled both get 200; only a
 * genuine failure returns non-2xx, which asks Stripe to retry and surfaces the problem in its
 * dashboard.
 */
@RestController
@RequestMapping("/api/webhooks/stripe")
class StripeWebhookController(
    private val stripeGateway: StripeGateway,
    private val stripeWebhookService: StripeWebhookService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun receive(
        @RequestBody payload: String,
        @RequestHeader(name = STRIPE_SIGNATURE_HEADER, required = false) signature: String?,
    ): ResponseEntity<Map<String, String>> {
        // Verified before anything else looks at the payload. Until this returns, the body is
        // an unauthenticated string from the open internet.
        val event = stripeGateway.verifyAndParse(payload, signature)

        return when (val outcome = stripeWebhookService.handle(event)) {
            is WebhookOutcome.Applied -> {
                log.info("Applied Stripe event {} ({}) to organization {}", event.id, event.type, outcome.organizationId)
                ResponseEntity.ok(mapOf("status" to "applied"))
            }

            is WebhookOutcome.Ignored -> {
                log.debug("Ignored Stripe event {} ({}): {}", event.id, event.type, outcome.reason)
                ResponseEntity.status(HttpStatus.OK).body(mapOf("status" to "ignored"))
            }
        }
    }

    private companion object {
        const val STRIPE_SIGNATURE_HEADER = "Stripe-Signature"
    }
}
