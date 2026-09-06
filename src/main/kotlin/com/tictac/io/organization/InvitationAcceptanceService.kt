package com.tictac.io.organization

import com.tictac.io.authentication.RegisterRequest
import com.tictac.io.authentication.RegistrationService
import com.tictac.io.common.security.SecureToken
import com.tictac.io.user.ActiveUser
import com.tictac.io.user.normalizeEmail
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** No invitation matches the presented token. */
class InvitationNotFoundException : RuntimeException("Invitation not found")

/**
 * The invitation exists but can no longer be used.
 *
 * Distinguishable from "not found" on purpose, unlike the deliberately opaque refresh-token
 * and OAuth-code failures. Only someone holding the raw token can reach this, and holding it
 * means they were the invitee - so "this invitation has expired, ask for a new one" tells
 * them something they need and an attacker nothing they could act on.
 */
class InvitationNoLongerValidException(message: String) : RuntimeException(message)

/** The registration in the request is for a different address than the invitation. */
class InvitationEmailMismatchException :
    RuntimeException("The invitation was issued to a different email address")

/**
 * Consuming an invitation, by an existing account or by a brand-new one.
 *
 * Both paths end in the same two writes - create the membership, stamp `accepted_at` - and
 * both run in one transaction, so the two states worth fearing are unreachable: a membership
 * with the invitation still pending, or an invitation marked accepted with nobody added.
 *
 * **Neither path takes an organization id.** It comes off the invitation row and nowhere
 * else, so there is no parameter to manipulate and no way to point an invitation for one
 * company at membership in another. That is the whole of the tenant-isolation story here.
 *
 * **Neither path takes an email as proof of identity.** The existing-user path reads it from
 * a signature-verified access token; the new-user path creates the account *at* the invited
 * address rather than at one the client names.
 */
@Service
class InvitationAcceptanceService(
    private val organizationInvitationRepository: OrganizationInvitationRepository,
    private val organizationRepository: OrganizationRepository,
    private val organizationMemberRepository: OrganizationMemberRepository,
    private val registrationService: RegistrationService,
    private val activeUser: ActiveUser,
) {

    /**
     * Accepted by someone already signed in.
     *
     * The caller's identity comes from [ActiveUser], which resolves the access token to a
     * live account - so the email compared below is one the server verified, never one the
     * request asserted. Bob holding Alice's invitation link gets 403: possession of the token
     * proves he has the *link*, not that he is the person it was addressed to.
     */
    @Transactional
    fun accept(request: AcceptInvitationRequest): AcceptedInvitationResponse {
        // Safe: validated as @NotBlank by the controller.
        val invitation = lockPendingInvitation(request.token!!)
        val user = activeUser.require()

        if (user.email != invitation.email) {
            throw AccessDeniedException("This invitation was issued to a different email address")
        }

        return consume(invitation, user.id!!)
    }

    /**
     * Accepted by someone creating their account in the same request.
     *
     * The account is created through the ordinary [RegistrationService] - same password
     * hashing, same email normalisation, same duplicate handling - because an invitation is
     * not a second way to make a user. Registration joins this transaction, so a failure
     * anywhere leaves neither an account nor a membership nor a consumed invitation.
     *
     * The registered address must be the invited one. Without that check, an invitation to
     * alice@example.com would be a licence to create bob@example.com inside Alice's company.
     */
    @Transactional
    fun registerAndAccept(request: RegisterWithInvitationRequest): AcceptedInvitationResponse {
        // Safe: all validated as @NotBlank by the controller.
        val invitation = lockPendingInvitation(request.token!!)

        if (normalizeEmail(request.email!!) != invitation.email) {
            throw InvitationEmailMismatchException()
        }

        val registered = registrationService.register(
            RegisterRequest(
                firstName = request.firstName,
                lastName = request.lastName,
                email = request.email,
                password = request.password,
            ),
        )

        return consume(invitation, registered.id)
    }

    /**
     * Resolves a token to an invitation that may still be used, with the row locked.
     *
     * The lock is what makes acceptance single-use under contention: two requests presenting
     * the same token serialise here, and the loser re-reads a row whose `accepted_at` the
     * winner has already committed. The checks that follow then reject it.
     */
    private fun lockPendingInvitation(rawToken: String): OrganizationInvitation {
        val invitation = organizationInvitationRepository.findAndLockByTokenHash(SecureToken.hash(rawToken))
            ?: throw InvitationNotFoundException()

        if (invitation.isAccepted()) {
            throw InvitationNoLongerValidException("This invitation has already been accepted")
        }
        if (!invitation.isPendingAt(Instant.now())) {
            throw InvitationNoLongerValidException("This invitation has expired")
        }

        return invitation
    }

    /**
     * The two writes, in the order that makes a partial failure harmless.
     *
     * Membership first: if it violates the `(organization_id, user_id)` unique index the
     * whole transaction rolls back with the invitation still pending, which is recoverable.
     * The reverse order could consume an invitation for somebody who never got in.
     */
    private fun consume(invitation: OrganizationInvitation, userId: UUID): AcceptedInvitationResponse {
        // The organization may have been closed between the invitation being issued and
        // presented. Joining a soft-deleted organization would create a membership nobody
        // can use - OrganizationAccess refuses the tenant outright - so it is refused here
        // where the invitee still gets a comprehensible answer.
        val organization = organizationRepository.findByIdAndDeletedAtIsNull(invitation.organizationId)
            ?: throw InvitationNoLongerValidException("The organization this invitation belongs to no longer exists")

        val membership = OrganizationMember(
            // From the invitation row, never from the request.
            organizationId = invitation.organizationId,
            userId = userId,
            // Always MEMBER. An invitation is not a route to a privileged role.
            role = OrganizationRole.MEMBER,
        )

        try {
            organizationMemberRepository.saveAndFlush(membership)
        } catch (ex: DataIntegrityViolationException) {
            // They joined between the invitation being issued and being accepted. The index
            // is the guarantee; this turns it into an answer rather than a 500.
            throw AlreadyOrganizationMemberException()
        }

        val now = Instant.now()
        invitation.acceptAt(now)
        organizationInvitationRepository.saveAndFlush(invitation)

        return AcceptedInvitationResponse(
            organizationId = organization.id!!,
            organizationName = organization.name,
            userId = userId,
            role = OrganizationRole.MEMBER,
            acceptedAt = now,
        )
    }
}
