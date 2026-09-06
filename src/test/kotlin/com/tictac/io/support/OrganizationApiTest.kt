package com.tictac.io.support

import com.jayway.jsonpath.JsonPath
import com.tictac.io.billing.BillingCustomer
import com.tictac.io.billing.BillingInterval
import com.tictac.io.billing.BillingProvider
import com.tictac.io.billing.OrganizationLicenses
import com.tictac.io.billing.Subscription
import com.tictac.io.billing.SubscriptionStatus
import com.tictac.io.organization.OrganizationMember
import com.tictac.io.organization.OrganizationRole
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** An account plus the tokens it signed in with, so a test can act as that person. */
data class TestUser(val id: UUID, val email: String, val tokens: TokenPair) {
    val accessToken: String get() = tokens.accessToken
}

/**
 * Organization helpers on top of [AuthenticatedApiTest]. Adds no bean overrides, so tests
 * extending this still share the one Spring context and container.
 */
abstract class OrganizationApiTest : AuthenticatedApiTest() {

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private var accountCounter = 0

    /** A fresh registered-and-signed-in account. Emails are unique per call. */
    protected fun newUser(email: String = "user${accountCounter++}@example.com"): TestUser {
        val (id, tokens) = registerAndLogin(email = email)
        return TestUser(id, email, tokens)
    }

    /** Creates an organization through the real endpoint; [owner] becomes its OWNER. */
    protected fun createOrganization(owner: TestUser, name: String = "Acme Consulting"): UUID {
        val body = postJson("/api/organizations", """{"name":"$name"}""", owner.accessToken)
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        return UUID.fromString(JsonPath.read(body, "$.id"))
    }

    /**
     * Seeds a membership directly.
     *
     * There is no API for this on purpose - joining an organization will happen by
     * accepting an invitation, and that feature does not exist yet - so tests that need an
     * ADMIN or a second MEMBER in place have to write the row the invitation flow will
     * eventually write. Everything actually under test (role checks, tenant isolation)
     * still goes through the real endpoints.
     */
    protected fun addMember(organizationId: UUID, user: TestUser, role: OrganizationRole): OrganizationMember =
        organizationMemberRepository.saveAndFlush(
            OrganizationMember(organizationId = organizationId, userId = user.id, role = role),
        )

    protected fun roleOf(organizationId: UUID, user: TestUser): OrganizationRole? =
        organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, user.id)?.role

    /** Creates a project through the real endpoint. [creator] must be an OWNER or ADMIN. */
    protected fun createProject(
        organizationId: UUID,
        creator: TestUser,
        name: String = "Website Redesign",
        description: String? = null,
    ): UUID {
        val body = if (description == null) {
            """{"name":"$name"}"""
        } else {
            """{"name":"$name","description":"$description"}"""
        }

        val response = postJson("/api/organizations/$organizationId/projects", body, creator.accessToken)
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    /** Assigns [user] to a project through the real endpoint. [caller] must be OWNER or ADMIN. */
    protected fun assignToProject(
        organizationId: UUID,
        projectId: UUID,
        user: TestUser,
        caller: TestUser,
    ) {
        postJson(
            "/api/organizations/$organizationId/projects/$projectId/members",
            """{"userId":"${user.id}"}""",
            caller.accessToken,
        ).andExpect(status().isCreated)
    }

    protected fun isAssigned(projectId: UUID, user: TestUser): Boolean =
        projectMemberRepository.findByProjectIdAndUserId(projectId, user.id) != null

    /** Archives a project through the real endpoint. [caller] must be OWNER or ADMIN. */
    protected fun archiveProject(organizationId: UUID, projectId: UUID, caller: TestUser) {
        patchJson(
            "/api/organizations/$organizationId/projects/$projectId",
            """{"isActive":false}""",
            caller.accessToken,
        ).andExpect(status().isOk)
    }

    /** Creates a project category through the real endpoint. [creator] must be OWNER or ADMIN. */
    protected fun createCategory(
        organizationId: UUID,
        projectId: UUID,
        creator: TestUser,
        name: String = "Development",
        description: String? = null,
    ): UUID {
        val descriptionField = if (description == null) "" else ""","description":"$description""""
        val response = postJson(
            "/api/organizations/$organizationId/projects/$projectId/categories",
            """{"name":"$name"""" + descriptionField + "}",
            creator.accessToken,
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    /** Retires a category through the real endpoint. [caller] must be OWNER or ADMIN. */
    protected fun archiveCategory(
        organizationId: UUID,
        projectId: UUID,
        categoryId: UUID,
        caller: TestUser,
    ) {
        patchJson(
            "/api/organizations/$organizationId/projects/$projectId/categories/$categoryId",
            """{"isActive":false}""",
            caller.accessToken,
        ).andExpect(status().isOk)
    }

    /** Starts a timer through the real endpoint and returns the new entry's id. */
    protected fun startTimer(
        organizationId: UUID,
        projectId: UUID,
        user: TestUser,
        title: String = DEFAULT_TIME_ENTRY_TITLE,
        description: String? = null,
        billable: Boolean = false,
        categoryId: UUID? = null,
    ): UUID {
        val descriptionField = if (description == null) "" else ""","description":"$description""""
        val categoryField = if (categoryId == null) "" else ""","projectCategoryId":"$categoryId""""
        val body = """{"projectId":"$projectId","title":"$title","billable":$billable""" +
            descriptionField + categoryField + "}"

        val response = postJson(
            "/api/organizations/$organizationId/time-entries/timer",
            body,
            user.accessToken,
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    /** Creates a manual entry through the real endpoint and returns its id. */
    protected fun createTimeEntry(
        organizationId: UUID,
        projectId: UUID,
        user: TestUser,
        startedAt: Instant,
        endedAt: Instant,
        title: String = DEFAULT_TIME_ENTRY_TITLE,
        description: String? = null,
        billable: Boolean = false,
        categoryId: UUID? = null,
    ): UUID {
        val descriptionField = if (description == null) "" else ""","description":"$description""""
        val categoryField = if (categoryId == null) "" else ""","projectCategoryId":"$categoryId""""
        val response = postJson(
            "/api/organizations/$organizationId/time-entries",
            """{"projectId":"$projectId","title":"$title","startedAt":"$startedAt"""" +
                ""","endedAt":"$endedAt","billable":$billable""" + descriptionField + categoryField + "}",
            user.accessToken,
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    /** Issues an invitation through the real endpoint and returns the raw token. */
    protected fun inviteToOrganization(
        organizationId: UUID,
        email: String,
        caller: TestUser,
    ): String {
        val body = postJson(
            "/api/organizations/$organizationId/invitations",
            """{"email":"$email"}""",
            caller.accessToken,
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        return JsonPath.read(body, "$.token")
    }

    /**
     * Pushes an invitation past its expiry, standing in for the passage of a week.
     *
     * A native update rather than an entity save: `expires_at` is mapped non-updatable on
     * purpose, so that nothing in production can quietly extend an invitation's life. A test
     * that needs an expired one has to go round the mapping, which is the right amount of
     * friction for something no application code should ever do.
     */
    protected fun expireInvitation(invitationId: UUID, by: Duration = Duration.ofDays(1)) {
        val expiresAt = Instant.now().minus(by)

        transactionTemplate.executeWithoutResult {
            entityManager
                .createNativeQuery(
                    "UPDATE organization_invitations SET created_at = :createdAt, expires_at = :expiresAt " +
                        "WHERE id = :id",
                )
                // Both timestamps move: an invitation expires because time passed, not
                // because it was issued with an expiry already behind it. The
                // `expires_at > created_at` check refuses the latter, correctly.
                .setParameter("createdAt", expiresAt.minus(Duration.ofDays(7)))
                .setParameter("expiresAt", expiresAt)
                .setParameter("id", invitationId)
                .executeUpdate()
        }
    }

    /**
     * Gives an organization an active subscription with [seats] paid seats.
     *
     * Stands in for a completed checkout plus the webhook that confirms it - there is no API
     * that creates a subscription locally, and there should not be. Seeding the row is the
     * only way to put a test organization into the paid state without a Stripe account.
     *
     * [licenses] is the total the organization ends up holding, free included one and all -
     * so the Stripe quantity seeded is one fewer. The organization's own `license_count` is
     * written too, because that is the number every allocation decision reads; seeding only
     * the subscription would leave a test paying for licences the organization does not hold.
     *
     * Inviting somebody acquires a licence when none is vacant, and that calls Stripe - so a
     * test that seeds enough licences up front needs no Stripe stand-in at all.
     */
    protected fun subscribeOrganization(
        organizationId: UUID,
        licenses: Int,
        interval: BillingInterval = BillingInterval.MONTHLY,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    ): Subscription {
        setLicenseCount(organizationId, licenses)

        billingCustomerRepository.findByOrganizationIdAndProvider(organizationId, BillingProvider.STRIPE)
            ?: billingCustomerRepository.saveAndFlush(
                BillingCustomer(organizationId, BillingProvider.STRIPE, "cus_test_$organizationId"),
            )

        return subscriptionRepository.saveAndFlush(
            Subscription(
                organizationId = organizationId,
                billingInterval = interval,
                status = status,
                paidLicenses = OrganizationLicenses.paidLicensesFor(licenses.toLong()).toInt(),
                provider = BillingProvider.STRIPE,
            ).apply {
                providerSubscriptionId = "sub_test_$organizationId"
                providerItemId = "si_test_$organizationId"
            },
        )
    }

    /**
     * Sets the organization's licence count directly, with no subscription behind it.
     *
     * For tests that need room for people without a billing relationship to explain where it
     * came from - and for the one case that genuinely has no subscription: an organization
     * whose subscription ended but whose members are still in their licences.
     */
    protected fun setLicenseCount(organizationId: UUID, licenses: Int) {
        val organization = organizationRepository.findById(organizationId).orElseThrow()
        organization.licenseCount = licenses
        organizationRepository.saveAndFlush(organization)
    }

    /**
     * Marks an organization deleted directly, with no billing side effects.
     *
     * `DELETE /api/organizations/{id}` also stops the Stripe subscription renewing, which is
     * right but pulls a Stripe stand-in into every suite that merely needs a closed tenant to
     * point at. Tests about the *deletion* itself go through the endpoint - see
     * OrganizationSoftDeleteIntegrationTest.
     */
    protected fun closeOrganization(organizationId: UUID) {
        val organization = organizationRepository.findById(organizationId).orElseThrow()
        organization.deletedAt = Instant.now()
        organizationRepository.saveAndFlush(organization)
    }

    /** Licences the organization holds. */
    protected fun licenseCountOf(organizationId: UUID): Int =
        organizationRepository.findById(organizationId).orElseThrow().licenseCount

    /** Licences Stripe is billed for, or null when there is no subscription at all. */
    protected fun billedLicensesOf(organizationId: UUID): Int? =
        subscriptionRepository.findByOrganizationId(organizationId)?.paidLicenses

    protected fun memberCountOf(organizationId: UUID): Long =
        organizationMemberRepository.countByOrganizationId(organizationId)

    protected companion object {
        const val DEFAULT_TIME_ENTRY_TITLE = "Tracked work"
    }
}
