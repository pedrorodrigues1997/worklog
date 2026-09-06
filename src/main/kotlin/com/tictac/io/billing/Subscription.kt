package com.tictac.io.billing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * An organization's subscription, as Stripe last described it.
 *
 * Every field here is written from a webhook and read by the application; nothing is decided
 * locally. If this row and Stripe disagree, Stripe is right and this row is stale - which is
 * why the webhook path matters far more than any of the read paths.
 *
 * [organizationId] is immutable. A subscription moving between organizations would move the
 * money with it.
 */
@Entity
@Table(name = "subscriptions")
class Subscription(
    @Column(name = "organization_id", nullable = false, updatable = false)
    val organizationId: UUID,

    /**
     * How often the organization is billed. Read back from the Stripe price, never chosen
     * locally after checkout - a quantity change leaves the price, and so the interval, alone.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", nullable = false, length = 16)
    var billingInterval: BillingInterval,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: SubscriptionStatus,

    /**
     * The Stripe subscription's quantity: licenses this organization is **billed** for.
     *
     * One fewer than the licenses it holds, because the first is included free. The
     * organization's own count lives on `organizations.license_count`; this is what Stripe was
     * last told, and the two differ while a change is in flight or after somebody edited the
     * quantity in the Stripe dashboard.
     */
    @Column(name = "paid_licenses", nullable = false)
    var paidLicenses: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    var provider: BillingProvider,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    /** Stripe's own subscription id ("sub_..."). */
    @Column(name = "provider_subscription_id", length = 255)
    var providerSubscriptionId: String? = null

    /**
     * Stripe's subscription *item* id ("si_...").
     *
     * Stored because quantity lives on the item, not the subscription - changing the licence
     * count needs it, and without it every change would have to fetch the subscription from
     * Stripe first.
     */
    @Column(name = "provider_item_id", length = 255)
    var providerItemId: String? = null

    @Column(name = "current_period_start")
    var currentPeriodStart: Instant? = null

    @Column(name = "current_period_end")
    var currentPeriodEnd: Instant? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    /**
     * When cancellation was *requested* - Stripe's `canceled_at` - not when access ends.
     *
     * A subscription cancelled at period end has this set while [status] is still ACTIVE and
     * [currentPeriodEnd] is in the future. Reading it as "access has ended" is the mistake
     * this comment exists to prevent.
     */
    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null

    /** Stripe's `cancel_at_period_end`: it will not renew, but it has not ended yet. */
    @Column(name = "cancel_at_period_end", nullable = false)
    var cancelAtPeriodEnd: Boolean = false

    /**
     * Whether Stripe would accept a licence-count change on this subscription right now: it
     * has told us both ids, and it is still billing.
     *
     * `@Transient` for the same reason the other entities' `isX()` helpers carry it -
     * Hibernate resolves property access through getters here and would otherwise look for a
     * `resizable` column.
     */
    @Transient
    fun isResizable(): Boolean =
        providerSubscriptionId != null && providerItemId != null && status.isBilling()

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    override fun toString(): String =
        "Subscription(id=$id, organizationId=$organizationId, interval=$billingInterval, status=$status)"


}

@Repository
interface SubscriptionRepository : JpaRepository<Subscription, UUID> {

    fun findByOrganizationId(organizationId: UUID): Subscription?

    fun findByProviderAndProviderSubscriptionId(
        provider: BillingProvider,
        providerSubscriptionId: String,
    ): Subscription?

    /**
     * The organization's subscription with the row locked for update.
     *
     * Webhook handling takes this lock before it writes. Two deliveries about the same
     * organization - a `created` and an `updated` arriving together, or a retry racing the
     * original - would otherwise both read the row and both write it, and the later write
     * would not necessarily be the newer state.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.organizationId = :organizationId")
    fun findAndLockByOrganizationId(@Param("organizationId") organizationId: UUID): Subscription?
}
