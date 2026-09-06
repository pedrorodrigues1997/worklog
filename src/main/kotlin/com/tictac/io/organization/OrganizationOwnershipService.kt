package com.tictac.io.organization

import com.tictac.io.user.User
import com.tictac.io.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** The request asks for something that is not a transfer at all. */
class InvalidOwnershipTransferException(message: String) : RuntimeException(message)

/**
 * The transfer is well-formed but cannot be applied to the organization as it stands -
 * the target's account is closed, or ownership moved while this request was in flight.
 */
class OwnershipTransferConflictException(message: String) : RuntimeException(message)

/**
 * Ownership transfer: the one operation allowed to move the OWNER role.
 *
 * Its own service because it is a distinct domain operation, not a role edit.
 * [OrganizationMembershipService.changeRole] deliberately refuses to assign or revoke
 * OWNER, since a role edit touches one member and a transfer necessarily touches two -
 * expressing it as two independent edits is exactly how an organization ends up with two
 * owners or none.
 */
@Service
class OrganizationOwnershipService(
    private val organizationAccess: OrganizationAccess,
    private val organizationMemberRepository: OrganizationMemberRepository,
    private val userRepository: UserRepository,
) {

    /**
     * Hands the organization to another of its members, demoting the caller to ADMIN.
     *
     * ADMIN rather than MEMBER for the outgoing owner: they keep every privilege the role
     * system can express short of ownership, which matches what a handover means in
     * practice - the person who built the organization stays able to run it. Nothing in
     * the domain argues for MEMBER, and demoting them further would be a second, separate
     * decision the caller did not ask for.
     *
     * **Statement order is load-bearing.** The partial unique index in V6 permits one
     * OWNER row per organization at any instant, so the demote must reach the database
     * before the promote. The two `saveAndFlush` calls pin that order - mutating both and
     * letting one flush sort it out would leave the ordering to Hibernate's action queue,
     * and getting it backwards means a constraint violation on every transfer. Between the
     * two statements the organization briefly has no owner; that state exists only inside
     * this transaction and no other session can observe it.
     */
    @Transactional
    fun transferOwnership(organizationId: UUID, request: TransferOwnershipRequest): OwnershipTransferResponse {
        val context = organizationAccess.require(organizationId, OrganizationRole.OWNER)

        // Safe: validated as @NotNull by the controller.
        val targetUserId = request.userId!!

        if (targetUserId == context.userId) {
            throw InvalidOwnershipTransferException("You already own this organization")
        }

        // Take the row lock before reading anything we intend to act on. Two concurrent
        // transfers of the same organization serialise here: the second one blocks, and
        // when it resumes the row it was waiting on no longer matches `role = OWNER`, so
        // it finds no owner to demote and fails cleanly instead of racing to a second
        // owner. See the class comment on OrganizationMemberRepository.findAndLockOwner.
        val currentOwner = organizationMemberRepository.findAndLockOwner(organizationId, OrganizationRole.OWNER)
        if (currentOwner == null || currentOwner.userId != context.userId) {
            throw OwnershipTransferConflictException(
                "Ownership of this organization changed while the request was in flight",
            )
        }

        // Membership first: a user who is not a member of *this* organization is reported
        // the same way as one who does not exist, so the endpoint cannot be used to probe
        // for account ids. Transfer by email does not exist for the same reason.
        val targetMembership = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
            ?: throw OrganizationMemberNotFoundException()

        // Locked, not merely read: this is the other half of the interlock with account
        // closure. Closure locks the same row before checking what its owner owns, so the
        // two operations cannot both look and then both act - whichever gets the lock
        // second sees the other's committed result and refuses.
        val targetUser = userRepository.findAndLockById(targetUserId)
            ?: throw OrganizationMemberNotFoundException()

        if (targetUser.deletedAt != null) {
            // The state this whole feature exists to prevent: an active organization owned
            // by an account that can never sign in again, and that is filtered out of its
            // own member listing.
            throw OwnershipTransferConflictException("Cannot transfer ownership to a closed account")
        }

        currentOwner.role = OrganizationRole.ADMIN
        organizationMemberRepository.saveAndFlush(currentOwner)

        targetMembership.role = OrganizationRole.OWNER
        organizationMemberRepository.saveAndFlush(targetMembership)

        // Both sides reported, so a client sees exactly what changed without a follow-up
        // read. Both users are already loaded - the caller by OrganizationAccess, the
        // target by the lock above - so this costs no extra queries.
        return OwnershipTransferResponse(
            organizationId = context.organizationId,
            previousOwner = memberResponse(context.user, currentOwner),
            newOwner = memberResponse(targetUser, targetMembership),
        )
    }

    private fun memberResponse(user: User, membership: OrganizationMember) =
        OrganizationMemberResponse(
            userId = user.id!!,
            firstName = user.firstName,
            lastName = user.lastName,
            email = user.email,
            role = membership.role,
            joinedAt = membership.createdAt,
        )
}
