package com.tictac.io.billing

import com.tictac.io.organization.Organization
import com.tictac.io.organization.OrganizationInvitationRepository
import com.tictac.io.organization.OrganizationMemberRepository
import com.tictac.io.organization.OrganizationRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * A licence was needed and the organization has no live subscription to add one to.
 *
 * The organization is holding its one free licence and has never bought any, or its
 * subscription has ended. Either way there is no card on file, so a licence cannot simply be
 * acquired - somebody has to check out first.
 */
class NoSubscriptionForLicenseException(val licenseCount: Long) :
    RuntimeException(
        "This organization holds $licenseCount licence(s) and has no active subscription to add another to; " +
            "start checkout to buy more",
    )

/** Reducing the licence count below the members occupying licences. */
class LicensesOccupiedException(val licensesRequested: Long, val minimumLicenses: Long) :
    RuntimeException(
        "This organization needs at least $minimumLicenses licence(s) for its current members; " +
            "remove members before reducing to $licensesRequested",
    )

/** Changing the licence count on an organization that has never bought a subscription. */
class NoSubscriptionToResizeException :
    RuntimeException("This organization has no subscription yet; start checkout to buy its first licences")

/**
 * Licences: how many the organization holds, who is occupying them, and what Stripe is told.
 *
 * ### The two operations, and why they are different
 *
 * - [acquireLicenseForMember] runs when an administrator **invites** somebody. If a licence is
 *   vacant the invitee takes it and nothing is billed; if none is, one is acquired - the Stripe
 *   quantity goes up by one - and the invitation then goes out against it.
 * - [changeLicenseCount] is the **explicit** operation: an administrator setting the number
 *   outright, up or down. This is the only way a licence is ever given up.
 *
 * Both are administrator actions, and that is deliberate. The person who spends the money is
 * always somebody authorised to spend it: the invitee's click never moves the bill, because by
 * the time they click, the licence they are about to occupy has already been paid for.
 *
 * ### What removing a member does
 *
 * Nothing, here. It vacates a licence and leaves the count alone, so the organization keeps
 * paying for it and can put the next person straight into it. Giving a licence up is
 * [changeLicenseCount] and nothing else - see the product rule in
 * [OrganizationLicenses].
 *
 * ### The lock
 *
 * Everything that reads a count and then acts on it takes `SELECT ... FOR UPDATE` on the
 * *organization* row first. It always exists (the subscription may not), it holds across
 * application instances (an in-memory lock would not), and it is the same row `license_count`
 * lives on - so the lock and the number it guards cannot drift apart.
 */
@Service
class OrganizationLicenseService(
    private val organizationRepository: OrganizationRepository,
    private val organizationMemberRepository: OrganizationMemberRepository,
    private val organizationInvitationRepository: OrganizationInvitationRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val stripeGateway: StripeGateway,
    private val entityManager: EntityManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * How an organization's licences currently stand.
     *
     * Read without a lock: this is a report, and by the time a client renders it the numbers
     * may have moved anyway. Every decision made *on* these numbers re-reads them under the
     * lock.
     */
    fun stateOf(organization: Organization): LicenseState {
        val organizationId = organization.id!!
        val licenseCount = organization.licenseCount.toLong()
        val members = organizationMemberRepository.countByOrganizationId(organizationId)
        val pending = pendingInvitationsFor(organizationId)
        val subscription = subscriptionRepository.findByOrganizationId(organizationId)

        return LicenseState(
            licenseCount = licenseCount,
            membersOccupying = members,
            pendingInvitations = pending,
            vacantLicenses = OrganizationLicenses.vacantLicensesFor(licenseCount, members),
            licensesAvailable = OrganizationLicenses.licensesAvailableFor(licenseCount, members, pending),
            paidLicenses = OrganizationLicenses.paidLicensesFor(licenseCount),
            // What Stripe was last told, which is 0 whenever there is nothing live to bill.
            billedLicenses = subscription?.takeIf { it.status.isBilling() }?.paidLicenses?.toLong() ?: 0,
            billingInterval = subscription?.billingInterval,
            subscriptionStatus = subscription?.status,
        )
    }

    /**
     * Makes sure a licence is waiting for one more person, acquiring one if it has to.
     *
     * Called when an invitation is **issued**, inside the issuing transaction and with the
     * organization row already locked by [lockOrganization]. Two outcomes:
     *
     * - a licence is free (vacant, and not already promised to another outstanding invitation)
     *   → nothing happens, and nothing is billed. This is the reused-vacant-licence case, and
     *   it is the common one for a team backfilling a role.
     * - none is free → the licence count goes up by one and Stripe's quantity goes up with it,
     *   charged to the card already on file and prorated by Stripe.
     *
     * Stripe is called **before** the local write commits, inside this transaction, so a
     * refusal rolls the whole invitation back rather than leaving an invitation outstanding
     * against a licence nobody is paying for. That does hold the organization's row lock
     * across a network call; it is bounded by Stripe's timeout, scoped to one organization,
     * and is the same serialisation the arithmetic needs anyway.
     */
    fun acquireLicenseForMember(organization: Organization) {
        val organizationId = organization.id!!
        val members = organizationMemberRepository.countByOrganizationId(organizationId)
        val pending = pendingInvitationsFor(organizationId)
        val licenseCount = organization.licenseCount.toLong()

        if (OrganizationLicenses.hasLicenseAvailable(licenseCount, members, pending)) {
            // A vacant licence is reused. No Stripe call, no charge, no new licence.
            return
        }

        val target = licenseCount + 1
        val subscription = subscriptionRepository.findByOrganizationId(organizationId)
            ?.takeIf { it.isResizable() }
            ?: throw NoSubscriptionForLicenseException(licenseCount)

        applyToStripe(subscription, OrganizationLicenses.paidLicensesFor(target))
        organization.licenseCount = target.toInt()
        organizationRepository.saveAndFlush(organization)

        log.info("Organization {} acquired a licence for a new member: now {}", organizationId, target)
    }

    /**
     * Refuses unless a licence is genuinely free for somebody about to become a member.
     *
     * The check made at **acceptance**, with the organization locked. A licence was already
     * acquired for this person when they were invited, so this normally passes - it is here
     * for the cases where it should not: an administrator reduced the count in between, or
     * somebody else took the licence first.
     *
     * Outstanding invitations are not subtracted here. The invitation being accepted is itself
     * one of them, and the person is becoming a member this instant; what matters is only
     * whether the organization holds a licence they can occupy.
     */
    fun requireLicenseForJoiningMember(organization: Organization) {
        val organizationId = organization.id!!
        val members = organizationMemberRepository.countByOrganizationId(organizationId)
        val licenseCount = organization.licenseCount.toLong()

        if (members >= licenseCount) {
            throw NoLicenseAvailableException(
                licenseCount = licenseCount,
                membersOccupying = members,
            )
        }
    }

    /**
     * Sets how many licences the organization holds. The explicit operation, up or down.
     *
     * **Raising** charges the card already on file - no checkout, no redirect, no card
     * re-entry - and Stripe prorates for the remainder of the period, monthly or annual alike.
     * Checkout exists for exactly one moment in an organization's life: the first purchase,
     * when there is no card yet.
     *
     * **Lowering** stops at [OrganizationLicenses.minimumLicensesFor]. Going below would mean
     * the application choosing which colleague to turn out; the administrator removes members
     * first, then reduces. Reaching the free included licence cancels the subscription outright
     * rather than leaving it alive at quantity zero, which Stripe does not treat as reliably
     * free and which still reads as paid in the dashboard.
     *
     * Set, never incremented: the caller sends the whole number, so sending it twice does
     * nothing the second time and a quantity that has drifted is corrected rather than
     * compounded.
     */
    @Transactional
    fun changeLicenseCount(organizationId: UUID, licenses: Long): Organization {
        val organization = lockOrganization(organizationId)
            ?: throw IllegalStateException("Organization $organizationId disappeared while changing licences")

        val members = organizationMemberRepository.countByOrganizationId(organizationId)
        val minimum = OrganizationLicenses.minimumLicensesFor(members)

        if (licenses < minimum) {
            throw LicensesOccupiedException(licensesRequested = licenses, minimumLicenses = minimum)
        }

        if (licenses == organization.licenseCount.toLong()) return organization

        val paid = OrganizationLicenses.paidLicensesFor(licenses)
        val subscription = subscriptionRepository.findByOrganizationId(organizationId)?.takeIf { it.isResizable() }

        when {
            // Down to the included licence: there is nothing left to bill for.
            paid == 0L -> subscription?.let { cancel(it) } ?: throw NoSubscriptionToResizeException()
            subscription != null -> applyToStripe(subscription, paid)
            else -> throw NoSubscriptionToResizeException()
        }

        organization.licenseCount = licenses.toInt()
        organizationRepository.saveAndFlush(organization)

        log.info("Organization {} now holds {} licence(s), {} billed", organizationId, licenses, paid)

        return organization
    }

    /**
     * Stops billing an organization that is being deleted.
     *
     * **At period end by default.** The organization has paid for the current period; ending it
     * on the spot would either take that away or force a refund nobody asked for. Stripe keeps
     * the subscription `active` until the period runs out, so [Subscription.status] deliberately
     * does *not* change here - only [Subscription.cancelAtPeriodEnd], and
     * [Subscription.cancelledAt], which records when cancellation was *asked for*. The webhook
     * flips the status when Stripe actually ends it.
     *
     * [immediately] is the opt-in escape hatch, and it is the caller's decision rather than
     * this method's: it ends the subscription now and Stripe credits the unused remainder.
     *
     * Does nothing when there is no subscription, when one has already stopped billing, or
     * when cancellation is already scheduled - so deleting is safe to retry.
     *
     * Called from inside the deletion transaction and **before** it commits: if Stripe refuses,
     * the whole delete rolls back. That is the right way round. An organization that is still
     * alive can be deleted again; one that is unreachable *and* still being charged cannot be
     * fixed through the API at all, because [OrganizationAccess] refuses a deleted tenant.
     */
    @Transactional
    fun cancelForOrganizationDeletion(organizationId: UUID, immediately: Boolean = false) {
        val subscription = subscriptionRepository.findByOrganizationId(organizationId)
            ?.takeIf { it.providerSubscriptionId != null && it.status.isBilling() }
            ?: return

        if (immediately) {
            cancel(subscription)
            return
        }

        if (subscription.cancelAtPeriodEnd) return

        stripeGateway.cancelSubscriptionAtPeriodEnd(subscription.providerSubscriptionId!!)

        // Status stays as Stripe has it - still active, still paid for, simply not renewing.
        subscription.cancelAtPeriodEnd = true
        subscription.cancelledAt = subscription.cancelledAt ?: Instant.now()
        subscriptionRepository.saveAndFlush(subscription)

        log.info(
            "Organization {} deleted: subscription will not renew after {}",
            organizationId,
            subscription.currentPeriodEnd,
        )
    }

    /**
     * Takes the organization's licence lock and returns the row, **re-read under the lock**.
     *
     * The refresh is load-bearing, not defensive. Every caller has already touched the
     * organization on the way in - `OrganizationAccess` loads it to authorise the request -
     * so it is in the persistence context before this runs, and Hibernate answers the locked
     * query with that same instance. The row lock is taken correctly; the *fields* are
     * whatever they were when it was first loaded. Reading `license_count` off that instance
     * means reading a value from before the lock, which is exactly what the lock exists to
     * prevent: two requests would serialise properly and then both act on the same stale
     * count.
     *
     * Callers must already be inside a transaction; the lock releases when it commits. Safe to
     * refresh here because every caller locks *before* it mutates anything.
     */
    fun lockOrganization(organizationId: UUID): Organization? {
        val organization = organizationRepository.findAndLockById(organizationId) ?: return null
        entityManager.refresh(organization)

        return organization
    }

    /** Invitations outstanding right now, each holding a licence. */
    private fun pendingInvitationsFor(organizationId: UUID): Long =
        organizationInvitationRepository
            .countByOrganizationIdAndAcceptedAtIsNullAndExpiresAtAfter(organizationId, Instant.now())

    /**
     * Sends the new quantity to Stripe and mirrors it locally.
     *
     * The local write is optimistic and the `customer.subscription.updated` webhook Stripe
     * sends in response is authoritative - it confirms, or corrects, this moments later.
     * Writing through means the API reflects the change immediately instead of appearing to
     * have ignored it until a webhook lands.
     */
    private fun applyToStripe(subscription: Subscription, paidLicenses: Long) {
        stripeGateway.updateSubscriptionQuantity(
            subscriptionId = subscription.providerSubscriptionId!!,
            itemId = subscription.providerItemId!!,
            quantity = paidLicenses,
        )

        subscription.paidLicenses = paidLicenses.toInt()
        subscriptionRepository.saveAndFlush(subscription)
    }

    /**
     * Ends the subscription instead of leaving it at quantity zero.
     *
     * Stripe does not treat zero as a reliable "bill nothing" across price configurations, and
     * a subscription sitting active at zero still reads as paid in the dashboard and in the
     * portal. Cancelling says what actually happened: this organization is back to its one
     * free licence. Coming back means checking out once more, which is the honest price of
     * having stopped paying.
     */
    private fun cancel(subscription: Subscription) {
        stripeGateway.cancelSubscription(subscription.providerSubscriptionId!!)

        subscription.paidLicenses = 0
        subscription.status = SubscriptionStatus.CANCELED
        // When cancellation was *asked for*, not when access ends - see the note on the field.
        subscription.cancelledAt = subscription.cancelledAt ?: Instant.now()
        subscriptionRepository.saveAndFlush(subscription)

        log.info(
            "Cancelled subscription for organization {}: back to the free included licence",
            subscription.organizationId,
        )
    }
}

/** The organization holds no licence a new member could occupy. */
class NoLicenseAvailableException(val licenseCount: Long, val membersOccupying: Long) :
    RuntimeException(
        "This organization's $licenseCount licence(s) are all occupied by its $membersOccupying member(s)",
    )

/**
 * An organization's licences at one instant.
 *
 * Every number a client needs to explain the situation to a human, so it never has to do the
 * arithmetic itself and arrive at a different answer than the server would.
 */
data class LicenseState(
    val licenseCount: Long,
    val membersOccupying: Long,
    val pendingInvitations: Long,
    val vacantLicenses: Long,
    val licensesAvailable: Long,
    val paidLicenses: Long,
    val billedLicenses: Long,
    val billingInterval: BillingInterval?,
    val subscriptionStatus: SubscriptionStatus?,
)
