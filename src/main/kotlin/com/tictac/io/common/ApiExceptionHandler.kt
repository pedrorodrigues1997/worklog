package com.tictac.io.common

import com.tictac.io.authentication.EmailAlreadyRegisteredException
import com.tictac.io.authentication.InvalidCredentialsException
import com.tictac.io.authentication.oauth.InvalidLoginCodeException
import com.tictac.io.authentication.oauth.InvalidOAuthIdentityException
import com.tictac.io.authentication.oauth.OAuthLinkingNotAllowedException
import com.tictac.io.authentication.token.InvalidRefreshTokenException
import com.tictac.io.organization.InvalidOwnershipTransferException
import com.tictac.io.billing.NoLicenseAvailableException
import com.tictac.io.billing.NoSubscriptionToResizeException
import com.tictac.io.billing.LicensesOccupiedException
import com.tictac.io.billing.NoSubscriptionForLicenseException
import com.tictac.io.billing.AlreadySubscribedException
import com.tictac.io.billing.BillingNotConfiguredException
import com.tictac.io.billing.IntervalNotPurchasableException
import com.tictac.io.billing.InvalidWebhookPayloadException
import com.tictac.io.billing.InvalidWebhookSignatureException
import com.tictac.io.billing.StripeUnavailableException
import com.tictac.io.billing.UnmappableStripeStateException
import com.tictac.io.organization.AlreadyOrganizationMemberException
import com.tictac.io.organization.InvitationAlreadyPendingException
import com.tictac.io.organization.InvitationEmailMismatchException
import com.tictac.io.organization.InvitationNoLongerValidException
import com.tictac.io.organization.InvitationNotFoundException
import com.tictac.io.organization.OrganizationMemberNotFoundException
import com.tictac.io.organization.OrganizationNotFoundException
import com.tictac.io.organization.OwnershipTransferConflictException
import com.tictac.io.project.DuplicateProjectCategoryNameException
import com.tictac.io.project.ProjectAssignmentConflictException
import com.tictac.io.project.ProjectCategoryNotActiveException
import com.tictac.io.project.ProjectCategoryNotFoundException
import com.tictac.io.project.ProjectCategoryValidationException
import com.tictac.io.project.ProjectNotActiveException
import com.tictac.io.project.ProjectMemberNotFoundException
import com.tictac.io.project.ProjectNotFoundException
import com.tictac.io.timetracking.TimeEntryNotFoundException
import com.tictac.io.timetracking.TimeEntryNotRunningException
import com.tictac.io.timetracking.TimeEntryValidationException
import com.tictac.io.timetracking.TimerAlreadyRunningException
import com.tictac.io.user.AccountClosureBlockedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Error responses use RFC 9457 `application/problem+json` ([ProblemDetail]).
 *
 * Extending [ResponseEntityExceptionHandler] means the framework's own exceptions
 * (malformed JSON, wrong method, unsupported media type, ...) keep their correct status
 * codes and safe generic messages; we only add the cases Spring cannot classify. It also
 * keeps the catch-all below from swallowing them, since a more specific handler in the
 * same advice always wins.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Bean Validation failures, reported per field so a form can highlight them. */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed")
        problem.title = "Validation failed"
        problem.setProperty(
            "errors",
            ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "is invalid") },
        )
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request)
    }

    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    fun handleEmailAlreadyRegistered(ex: EmailAlreadyRegisteredException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Email already registered"
        }

    /**
     * Login failure. Deliberately identical whether the address is unknown, the password
     * is wrong, or the account is deleted - the response must not help enumerate accounts.
     */
    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password").apply {
            title = "Authentication failed"
        }

    /** Unknown, expired, already-rotated or revoked refresh token - all indistinguishable. */
    @ExceptionHandler(InvalidRefreshTokenException::class)
    fun handleInvalidRefreshToken(ex: InvalidRefreshTokenException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or has expired").apply {
            title = "Authentication failed"
        }

    /** Unknown, expired or already-used OAuth login code - all indistinguishable. */
    @ExceptionHandler(InvalidLoginCodeException::class)
    fun handleInvalidLoginCode(ex: InvalidLoginCodeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Login code is invalid or has expired").apply {
            title = "Authentication failed"
        }

    /** The provider response was unusable. Reachable only outside the redirect flow. */
    @ExceptionHandler(InvalidOAuthIdentityException::class)
    fun handleInvalidOAuthIdentity(ex: InvalidOAuthIdentityException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "The identity provider response was not usable",
        ).apply { title = "Invalid identity" }

    /** A valid identity we decline to attach - unverified address, or a closed account. */
    @ExceptionHandler(OAuthLinkingNotAllowedException::class)
    fun handleOAuthLinkingNotAllowed(ex: OAuthLinkingNotAllowedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Cannot link this identity"
        }

    /**
     * The caller is not a member of that organization - or it does not exist, or it has
     * been soft-deleted. One response for all three: see OrganizationNotFoundException for
     * why distinguishing them would turn a leaked id into an existence oracle.
     */
    @ExceptionHandler(OrganizationNotFoundException::class)
    fun handleOrganizationNotFound(ex: OrganizationNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message).apply {
            title = "Organization not found"
        }

    /**
     * The caller is already authorised for the organization and the *target* member is
     * missing, so this reveals nothing they could not see in the member list.
     */
    @ExceptionHandler(OrganizationMemberNotFoundException::class)
    fun handleOrganizationMemberNotFound(ex: OrganizationMemberNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message).apply {
            title = "Member not found"
        }

    /**
     * The project does not exist, belongs to another organization, or is invisible to this
     * caller. One answer for all three: an organization MEMBER who is not assigned has no
     * legitimate way to learn a project exists, so a 403 here would leak its existence.
     */
    @ExceptionHandler(ProjectNotFoundException::class)
    fun handleProjectNotFound(ex: ProjectNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message).apply {
            title = "Project not found"
        }

    /** The caller can already see the project, so naming a missing assignment leaks nothing. */
    @ExceptionHandler(ProjectMemberNotFoundException::class)
    fun handleProjectMemberNotFound(ex: ProjectMemberNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message).apply {
            title = "Project member not found"
        }

    /** Already assigned, or the target's account is closed. */
    @ExceptionHandler(ProjectAssignmentConflictException::class)
    fun handleProjectAssignmentConflict(ex: ProjectAssignmentConflictException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Cannot assign this user"
        }

    /**
     * The entry does not exist, belongs to another organization, is deleted, or belongs to
     * another user and the caller is not an administrator. One answer for all four: a member
     * has no legitimate way to learn that a colleague's entry exists.
     */
    @ExceptionHandler(TimeEntryNotFoundException::class)
    fun handleTimeEntryNotFound(ex: TimeEntryNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message).apply {
            title = "Time entry not found"
        }

    /**
     * A timer is already running in this organization. A conflict rather than a validation
     * failure: the request is well-formed, and it is the state of the world that refuses it.
     * Nothing is stopped implicitly - the caller decides which timer should be running.
     */
    @ExceptionHandler(TimerAlreadyRunningException::class)
    fun handleTimerAlreadyRunning(ex: TimerAlreadyRunningException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Timer already running"
        }

    /** Stop called on an entry that is not running - including a second stop. */
    @ExceptionHandler(TimeEntryNotRunningException::class)
    fun handleTimeEntryNotRunning(ex: TimeEntryNotRunningException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Time entry is not running"
        }

    /**
     * New time cannot be recorded against an archived project. Distinguishable from "not
     * found" deliberately: the caller can see the project, so its state is not a secret, and
     * this is the one message that says what to do about it.
     */
    @ExceptionHandler(ProjectNotActiveException::class)
    fun handleProjectNotActive(ex: ProjectNotActiveException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Project is archived"
        }

    /**
     * A well-formed time-entry request carrying a value the domain refuses - a blank title,
     * an interval that runs backwards or spans more than a day.
     *
     * 422 rather than 400, which is reserved for a malformed request: Bean Validation still
     * answers "you omitted projectId" with 400 and a per-field map. The split is worth
     * keeping because the two need different handling by a client - one is a bug, the other
     * is something to show the user next to the field they typed.
     */
    @ExceptionHandler(TimeEntryValidationException::class)
    fun handleTimeEntryValidation(ex: TimeEntryValidationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.message).apply {
            title = "Invalid time entry"
        }

    /** The category does not exist, or belongs to a different project. One answer for both. */
    @ExceptionHandler(ProjectCategoryNotFoundException::class)
    fun handleProjectCategoryNotFound(ex: ProjectCategoryNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message).apply {
            title = "Project category not found"
        }

    /**
     * A retired category cannot be chosen for new work. Distinguishable from "not found"
     * deliberately: the caller can see it in the project's listing, so its state is not a
     * secret, and this is the message that says what to do about it.
     */
    @ExceptionHandler(ProjectCategoryNotActiveException::class)
    fun handleProjectCategoryNotActive(ex: ProjectCategoryNotActiveException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Category is archived"
        }

    /** Another category in the project already holds this name, ignoring case. */
    @ExceptionHandler(DuplicateProjectCategoryNameException::class)
    fun handleDuplicateProjectCategoryName(ex: DuplicateProjectCategoryNameException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Duplicate category name"
        }

    /** A category name or description that is well-formed JSON but not a usable value. */
    @ExceptionHandler(ProjectCategoryValidationException::class)
    fun handleProjectCategoryValidation(ex: ProjectCategoryValidationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.message).apply {
            title = "Invalid project category"
        }

    /**
     * A webhook whose signature is missing or does not verify.
     *
     * 400, and deliberately terse: the response says nothing about which check failed, and
     * nothing is logged that would echo the payload back. Anyone can POST to this endpoint;
     * only Stripe can sign for it.
     */
    @ExceptionHandler(InvalidWebhookSignatureException::class)
    fun handleInvalidWebhookSignature(ex: InvalidWebhookSignatureException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Webhook signature verification failed").apply {
            title = "Invalid webhook signature"
        }

    /**
     * A webhook that verified but carried an unusable body. 400, and separate from a
     * signature failure: only the holder of the signing secret can produce this, so it
     * reads as a malformed request rather than a forged one.
     */
    @ExceptionHandler(InvalidWebhookPayloadException::class)
    fun handleInvalidWebhookPayload(ex: InvalidWebhookPayloadException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message).apply {
            title = "Malformed webhook payload"
        }

    /**
     * Stripe described a status or a price this deployment cannot map.
     *
     * 500 on purpose. It makes the webhook non-2xx, so Stripe retries and shows the failure
     * in its dashboard - which is the only honest response to billing input we do not
     * understand. Guessing would either hand out paid features or cut off a paying customer.
     */
    @ExceptionHandler(UnmappableStripeStateException::class)
    fun handleUnmappableStripeState(ex: UnmappableStripeStateException): ProblemDetail {
        log.error("Unmappable Stripe state: {}", ex.message)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Billing state could not be processed",
        ).apply { title = "Unprocessable billing state" }
    }

    /** Billing is not configured in this deployment - a normal state locally. */
    @ExceptionHandler(BillingNotConfiguredException::class)
    fun handleBillingNotConfigured(ex: BillingNotConfiguredException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message).apply {
            title = "Billing not configured"
        }

    /**
     * Stripe refused or could not be reached. 502: the failure is upstream, and the detail
     * stays generic because Stripe's messages name customers, prices and internal ids.
     */
    @ExceptionHandler(StripeUnavailableException::class)
    fun handleStripeUnavailable(ex: StripeUnavailableException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "The billing provider is unavailable").apply {
            title = "Billing provider error"
        }

    /**
     * Every licence the organization holds is occupied, and it has no subscription to acquire
     * another on.
     *
     * 409, consistent with every other "the state of the world refuses this" answer in this
     * API. 402 Payment Required reads better in isolation but is reserved by RFC 9110 and
     * would be the only code of its kind here; the distinct `title` is what a client branches
     * on, and the count lets it explain the situation without another request.
     */
    @ExceptionHandler(NoSubscriptionForLicenseException::class)
    fun handleNoSubscriptionForLicense(ex: NoSubscriptionForLicenseException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "No subscription to add a licence to"
            setProperty("licenseCount", ex.licenseCount)
        }

    /**
     * A licence was expected to be free for somebody joining and was not.
     *
     * Reached at acceptance, after a licence was acquired for the invitee - so it means the
     * count moved in between, not that the invitation was wrong.
     */
    @ExceptionHandler(NoLicenseAvailableException::class)
    fun handleNoLicenseAvailable(ex: NoLicenseAvailableException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "No licence available"
            setProperty("licenseCount", ex.licenseCount)
            setProperty("membersOccupying", ex.membersOccupying)
        }

    /** Reducing the licence count below the members occupying licences. */
    @ExceptionHandler(LicensesOccupiedException::class)
    fun handleLicensesOccupied(ex: LicensesOccupiedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Licences are occupied"
            setProperty("licensesRequested", ex.licensesRequested)
            setProperty("minimumLicenses", ex.minimumLicenses)
        }

    /** Changing the licence count before the organization has ever bought a subscription. */
    @ExceptionHandler(NoSubscriptionToResizeException::class)
    fun handleNoSubscriptionToResize(ex: NoSubscriptionToResizeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "No subscription yet"
        }

    /** No Stripe price is configured for the requested billing interval. */
    @ExceptionHandler(IntervalNotPurchasableException::class)
    fun handleIntervalNotPurchasable(ex: IntervalNotPurchasableException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message).apply {
            title = "Billing interval not available"
        }

    /** One subscription per organization; seats grow on the existing one. */
    @ExceptionHandler(AlreadySubscribedException::class)
    fun handleAlreadySubscribed(ex: AlreadySubscribedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Already subscribed"
        }

    /**
     * The address already belongs to a member of the organization. Raised both when issuing
     * an invitation and when consuming one, since a person can join in between.
     */
    @ExceptionHandler(AlreadyOrganizationMemberException::class)
    fun handleAlreadyOrganizationMember(ex: AlreadyOrganizationMemberException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Already a member"
        }

    /** A usable invitation to this address already exists; issuing a second is refused. */
    @ExceptionHandler(InvitationAlreadyPendingException::class)
    fun handleInvitationAlreadyPending(ex: InvitationAlreadyPendingException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Invitation already pending"
        }

    /**
     * No invitation matches the presented token - unknown, or never existed. Says nothing
     * about which, and cannot be reached without presenting a 256-bit value.
     */
    @ExceptionHandler(InvitationNotFoundException::class)
    fun handleInvitationNotFound(ex: InvitationNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message).apply {
            title = "Invitation not found"
        }

    /**
     * The invitation exists but is spent or lapsed. Distinguished from "not found" on
     * purpose: only the invitee can reach this, and "expired, ask for a new one" is the one
     * message that tells them what to do.
     */
    @ExceptionHandler(InvitationNoLongerValidException::class)
    fun handleInvitationNoLongerValid(ex: InvitationNoLongerValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Invitation is no longer valid"
        }

    /**
     * The registration in the request names a different address than the invitation.
     *
     * 422 rather than 403: there is no authenticated principal on that endpoint to forbid,
     * and the request is well-formed - it is the combination of values the domain refuses.
     * The authenticated-user mismatch is a 403, because there the caller has an identity and
     * it is the wrong one.
     */
    @ExceptionHandler(InvitationEmailMismatchException::class)
    fun handleInvitationEmailMismatch(ex: InvitationEmailMismatchException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.message).apply {
            title = "Invitation email mismatch"
        }

    /**
     * A transfer request that is not a transfer - today, only handing the organization to
     * yourself. A validation failure rather than a conflict: nothing about the state of
     * the organization would make it valid.
     */
    @ExceptionHandler(InvalidOwnershipTransferException::class)
    fun handleInvalidOwnershipTransfer(ex: InvalidOwnershipTransferException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message).apply {
            title = "Invalid ownership transfer"
        }

    /**
     * A well-formed transfer the organization's current state will not accept - a closed
     * target account, or ownership that moved while the request was in flight.
     */
    @ExceptionHandler(OwnershipTransferConflictException::class)
    fun handleOwnershipTransferConflict(ex: OwnershipTransferConflictException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Ownership transfer failed"
        }

    /**
     * The caller still owns organizations. The blocking organizations are listed so the
     * client can say which ones need handing over, rather than making the user hunt.
     * Safe to enumerate: the caller owns every one of them.
     */
    @ExceptionHandler(AccountClosureBlockedException::class)
    fun handleAccountClosureBlocked(ex: AccountClosureBlockedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message).apply {
            title = "Account still owns organizations"
            setProperty("organizations", ex.organizations.map { mapOf("id" to it.id, "name" to it.name) })
        }

    /**
     * Declared explicitly so the catch-all below cannot turn an authorisation failure
     * into a 500. Load-bearing now that organization roles are enforced: a member
     * attempting an administrative operation lands here.
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied").apply {
            title = "Forbidden"
        }

    /** Last resort. Log the cause for us, tell the client nothing about it. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error("Unhandled exception while processing request", ex)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred",
        ).apply { title = "Internal server error" }
    }
}
