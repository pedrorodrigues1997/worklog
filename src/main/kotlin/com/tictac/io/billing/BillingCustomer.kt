package com.tictac.io.billing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * The link between one organization and its customer record at a payment provider.
 *
 * The same shape as [com.tictac.io.authentication.oauth.UserIdentity], and for the same
 * reason: an internal entity, an external provider, and that provider's own identifier.
 *
 * This row is the **only** thing that maps an incoming Stripe event back to a TicTac
 * organization. Webhook payloads carry metadata we set ourselves, and metadata is editable in
 * the Stripe dashboard by anyone with access to it - so it is never read as an authorisation
 * input. The customer id is, because we created the customer and recorded the mapping here.
 *
 * Every field is immutable. A customer is created once per organization and never re-pointed.
 */
@Entity
@Table(name = "billing_customers")
class BillingCustomer(
    @Column(name = "organization_id", nullable = false, updatable = false)
    val organizationId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 32)
    val provider: BillingProvider,

    /** The provider's customer identifier ("cus_..."). */
    @Column(name = "provider_customer_id", nullable = false, updatable = false, length = 255)
    val providerCustomerId: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun toString(): String =
        "BillingCustomer(id=$id, organizationId=$organizationId, provider=$provider)"
}

interface BillingCustomerRepository : JpaRepository<BillingCustomer, UUID> {

    fun findByOrganizationIdAndProvider(organizationId: UUID, provider: BillingProvider): BillingCustomer?

    /** The webhook direction: provider customer id back to an organization. */
    fun findByProviderAndProviderCustomerId(
        provider: BillingProvider,
        providerCustomerId: String,
    ): BillingCustomer?
}

/**
 * A webhook event this backend has already handled.
 *
 * See V15 for the mechanism. The row is written in the same transaction as the state change
 * it authorises, so "recorded" and "applied" cannot come apart.
 */
@Entity
@Table(name = "billing_webhook_events")
class BillingWebhookEvent(
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 32)
    val provider: BillingProvider,

    @Column(name = "event_id", nullable = false, updatable = false, length = 255)
    val eventId: String,

    @Column(name = "event_type", nullable = false, updatable = false, length = 255)
    val eventType: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "received_at", nullable = false, updatable = false)
    var receivedAt: Instant = Instant.now()

    override fun toString(): String = "BillingWebhookEvent(eventId=$eventId, type=$eventType)"
}

interface BillingWebhookEventRepository : JpaRepository<BillingWebhookEvent, UUID> {
    fun findByProviderAndEventId(provider: BillingProvider, eventId: String): BillingWebhookEvent?
}
