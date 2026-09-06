package com.tictac.io.organization

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Issuing invitations, under the organization doing the inviting - so the tenant is in the
 * path and is checked before anything is written, like every other organization-scoped
 * operation.
 */
@RestController
@RequestMapping("/api/organizations/{organizationId}/invitations")
class OrganizationInvitationController(
    private val organizationInvitationService: OrganizationInvitationService,
) {

    /** OWNER and ADMIN only. The response carries the raw token; see [InvitationResponse]. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable organizationId: UUID,
        @Valid @RequestBody request: CreateInvitationRequest,
    ): InvitationResponse = organizationInvitationService.create(organizationId, request)
}

/**
 * Accepting an invitation.
 *
 * **Two endpoints rather than the one the brief sketched**, because the existing security
 * configuration forces the split and it is the right split anyway. The `@Order(1)` chain that
 * serves the public POSTs deliberately has *no* bearer-token support - see `SecurityConfig` -
 * so an endpoint on it cannot know who is calling. Acceptance by an existing user must know
 * exactly that, and must learn it from a verified token rather than from the request body.
 *
 * So:
 *
 * - [accept] sits on the default-deny chain and requires a valid access token. Identity comes
 *   from the token, and the invited address is checked against it.
 * - [registerAndAccept] is public, because the invitee has no account yet - and it takes the
 *   credentials registration has always required, because possessing an invitation is not the
 *   same as having proved who you are.
 *
 * Neither takes an organization id. There is no such parameter to tamper with: the
 * organization comes off the invitation row.
 */
@RestController
@RequestMapping("/api/invitations")
class InvitationAcceptanceController(
    private val invitationAcceptanceService: InvitationAcceptanceService,
) {

    /** Requires authentication. The caller's own email must be the invited one. */
    @PostMapping("/accept")
    fun accept(@Valid @RequestBody request: AcceptInvitationRequest): AcceptedInvitationResponse =
        invitationAcceptanceService.accept(request)

    /**
     * Public: creates the account and consumes the invitation in one transaction.
     *
     * 201, matching `POST /api/auth/register` - a user account is created here, which is the
     * part a client needs to know succeeded.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun registerAndAccept(
        @Valid @RequestBody request: RegisterWithInvitationRequest,
    ): AcceptedInvitationResponse = invitationAcceptanceService.registerAndAccept(request)
}
