package com.tictac.io.billing

import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Builds webhook requests the way Stripe does.
 *
 * The signature is computed here exactly as Stripe computes it - HMAC-SHA256 over
 * `"$timestamp.$payload"`, hex-encoded, in a `t=…,v1=…` header - so the tests exercise the
 * *real* verification path in [StripeGateway] rather than a mock of it. That matters more
 * here than anywhere else in the codebase: the signature is the entire authentication of a
 * public endpoint that writes billing state.
 *
 * Nothing in these tests reaches Stripe. The payloads are the JSON Stripe documents itself,
 * signed with the test secret from `application-test.properties`.
 */
object StripeWebhookSupport {

    const val TEST_WEBHOOK_SECRET = "whsec_test_secret_for_signature_verification"

    /** A `Stripe-Signature` header for [payload], valid as of [timestampSeconds]. */
    fun signatureHeader(
        payload: String,
        secret: String = TEST_WEBHOOK_SECRET,
        timestampSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))

        val signature = HexFormat.of()
            .formatHex(mac.doFinal("$timestampSeconds.$payload".toByteArray(Charsets.UTF_8)))

        return "t=$timestampSeconds,v1=$signature"
    }

    /**
     * A `customer.subscription.*` event body.
     *
     * Deliberately hand-written rather than produced by the Stripe SDK: this is the shape
     * that actually arrives over the wire, including `current_period_start`/`end` sitting on
     * the subscription *item* - which is where the current API puts them, and getting that
     * wrong is a silent null rather than an error.
     */
    fun subscriptionEvent(
        eventId: String,
        type: String,
        subscriptionId: String,
        customerId: String,
        status: String = "active",
        priceId: String = "price_test_monthly",
        quantity: Int = 5,
        currentPeriodStart: Long = 1_760_000_000,
        currentPeriodEnd: Long = 1_762_678_400,
        cancelAtPeriodEnd: Boolean = false,
        canceledAt: Long? = null,
        organizationMetadata: String? = null,
        includeItem: Boolean = true,
        apiVersion: String = com.stripe.Stripe.API_VERSION,
    ): String {
        val metadata = organizationMetadata
            ?.let { """"metadata":{"tictac_organization_id":"$it"},""" }
            ?: """"metadata":{},"""

        val items = if (!includeItem) {
            """"items":{"object":"list","data":[]},"""
        } else {
            """"items":{"object":"list","data":[{"id":"si_test","object":"subscription_item",""" +
                """"quantity":$quantity,""" +
                """"current_period_start":$currentPeriodStart,"current_period_end":$currentPeriodEnd,""" +
                """"price":{"id":"$priceId","object":"price"}}]},"""
        }

        val canceled = canceledAt?.let { """"canceled_at":$it,""" } ?: """"canceled_at":null,"""

        return """
        {
          "id": "$eventId",
          "object": "event",
          "api_version": "$apiVersion",
          "created": 1760000000,
          "type": "$type",
          "data": {
            "object": {
              "id": "$subscriptionId",
              "object": "subscription",
              "customer": "$customerId",
              "status": "$status",
              "cancel_at_period_end": $cancelAtPeriodEnd,
              $canceled
              $metadata
              $items
              "livemode": false
            }
          }
        }
        """.trimIndent()
    }
}
