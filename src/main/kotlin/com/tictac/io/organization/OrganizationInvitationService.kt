package com.tictac.io.organization

import com.tictac.io.common.security.SecureToken
import com.tictac.io.user.UserRepository
import com.tictac.io.user.normalizeEmail
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** The address already belongs to a member of this organization. */
class AlreadyOrganizationMemberException :
    RuntimeException("This person is already a member of this organization")

/** A usable invitation to this address already exists. */
class InvitationAlreadyPendingException :
    RuntimeException("An invitation to this address is already pending")

/**
 * Issuing invitations. OWNER and ADMIN only, through the same
 * [OrganizationAccess] gate every other organization-scoped operation uses.
 *
 * This is the missing half of onboarding: until now the only way into an organization was
 * founding it, and every test in this codebase had to write `organization_members` rows
 * directly to get a second person into a company.
 */
@Service
class OrganizationInvitationService(
    private val organizationAccess: OrganizationAccess,
    private val organizationInvitationRepository: OrganizationInvitationRepository,
    private val organizationMemberRepository: OrganizationMemberRepository,
    private val userRepository: UserRepository,
    private val properties: InvitationProperties,
) {

    /**
     * Creates an invitation and returns it, raw token included.
     *
     * The organization comes from the authorised path and is written onto the invitation
     * there and then. Nothing later re-reads an organization id from a request, which is what
     * makes it impossible to steer an acceptance into a different tenant.
     *
     * The token is generated here and hashed before it is stored, so this method is the only
     * place the raw value exists. It is returned to the inviter because email delivery does
     * not exist yet; it is never persisted and never logged.
     */
    @Transactional
    fun create(organizationId: UUID, request: CreateInvitationRequest): InvitationResponse {
        val context = organizationAccess.require(organizationId, OrganizationRole.OWNER, OrganizationRole.ADMIN)

        // Safe: the controller validates with @Valid. Normalised with the *same* function
        // registration and login use - a second normalisation strategy would mean an
        // invitation that no account could ever match.
        val email = normalizeEmail(request.email!!)

        requireNotAlreadyAMember(context.organizationId, email)
        clearLapsedInvitation(context.organizationId, email)

        val now = Instant.now()
        val rawToken = SecureToken.generate()

        val invitation = OrganizationInvitation(
            organizationId = context.organizationId,
            email = email,
            invitedByUserId = context.userId,
            tokenHash = SecureToken.hash(rawToken),
            expiresAt = now.plus(properties.ttl),
        ).apply { createdAt = now }

        val saved = try {
            // saveAndFlush so the partial unique index is evaluated here rather than at
            // commit: two administrators inviting the same person at once both pass the
            // check above, and only one can pass the index.
            organizationInvitationRepository.saveAndFlush(invitation)
        } catch (ex: DataIntegrityViolationException) {
            throw InvitationAlreadyPendingException()
        }

        return InvitationResponse(
            id = saved.id!!,
            organizationId = saved.organizationId,
            email = saved.email,
            invitedByUserId = saved.invitedByUserId,
            createdAt = saved.createdAt,
            expiresAt = saved.expiresAt,
            token = rawToken,
        )
    }

    /**
     * Refuses to invite someone who is already in the organization.
     *
     * Not a security control - the `(organization_id, user_id)` unique index remains the
     * actual guarantee against duplicate membership, and acceptance re-checks it anyway.
     * This is here so an administrator gets a clear conflict instead of a pending invitation
     * that could only ever fail.
     */
    private fun requireNotAlreadyAMember(organizationId: UUID, email: String) {
        val user = userRepository.findByEmail(email) ?: return

        if (organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, user.id!!) != null) {
            throw AlreadyOrganizationMemberException()
        }
    }

    /**
     * Makes room for a replacement when the previous invitation has lapsed.
     *
     * The partial unique index covers every un-accepted invitation, not just pending ones -
     * "pending" depends on the current time, which an index predicate cannot - so an expired
     * row would otherwise block re-inviting somebody forever. Deleting it is honest: it was
     * never accepted, it can never be accepted now, and it is ephemeral onboarding state
     * rather than a record anyone audits. A *pending* one is left alone and reported as the
     * conflict it is.
     */
    private fun clearLapsedInvitation(organizationId: UUID, email: String) {
        val existing = organizationInvitationRepository
            .findByOrganizationIdAndEmailAndAcceptedAtIsNull(organizationId, email)
            ?: return

        if (existing.isPendingAt(Instant.now())) {
            throw InvitationAlreadyPendingException()
        }

        organizationInvitationRepository.delete(existing)
        organizationInvitationRepository.flush()
    }
}
