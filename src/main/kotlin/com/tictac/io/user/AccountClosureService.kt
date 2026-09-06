package com.tictac.io.user

import com.tictac.io.authentication.token.RefreshTokenRevoker
import com.tictac.io.common.security.CurrentUser
import com.tictac.io.organization.OrganizationMemberRepository
import com.tictac.io.organization.OrganizationResponse
import com.tictac.io.organization.OrganizationRole
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The account still owns organizations. [organizations] are the ones blocking closure,
 * reported back so the caller knows exactly what to hand over first.
 */
class AccountClosureBlockedException(val organizations: List<OrganizationResponse>) :
    RuntimeException("Transfer ownership of your organizations before closing your account")

/**
 * Closing an account.
 *
 * Soft delete, consistent with the rest of the model: `users.deleted_at` is stamped and
 * the row stays. Everything that reads a user already treats that as gone - login, the
 * OAuth paths, [ActiveUser.require], and the organization member listing - so closure is
 * a matter of setting the marker and making sure nothing outlives it.
 *
 * **Non-owner memberships survive closure.** A closed MEMBER or ADMIN keeps their
 * `organization_members` row, and this is a decision rather than an oversight:
 *
 * - the row is already inert. The account cannot authenticate, so the membership grants
 *   nothing, and `findMembersOfOrganization` filters closed accounts out of every listing;
 * - deleting it is irreversible and destroys the record of who was in an organization,
 *   which is the only trace left once time entries start referring to people;
 * - soft-deleting it instead would mean a `deleted_at` on `organization_members` - a whole
 *   membership lifecycle invented for a state nothing can observe.
 *
 * The cost is that an organization's member count and its `organization_members` row count
 * can differ. That matters exactly once, when seats are billed, and the fix there is to
 * count through `users.deleted_at` rather than to have thrown the rows away here.
 *
 * This service lives in `user` but depends on the organization domain, which is the one
 * place that dependency runs in this direction. Account closure is inherently a
 * cross-domain lifecycle rule: the constraint on closing an account is a fact about
 * organizations. Routing it through an event or an interface with a single implementation
 * would add indirection without adding a decision, so the dependency is direct and stated.
 */
@Service
class AccountClosureService(
    private val currentUser: CurrentUser,
    private val userRepository: UserRepository,
    private val organizationMemberRepository: OrganizationMemberRepository,
    private val refreshTokenRevoker: RefreshTokenRevoker,
) {

    /**
     * Closes the caller's own account.
     *
     * Refuses while the caller owns any *active* organization. The alternatives were all
     * worse: promoting some other member is a decision the API has no basis to make,
     * deleting the organization destroys a business's data as a side effect of one person
     * leaving, and dropping the OWNER membership silently leaves an active organization
     * that nobody can administer and nobody can be granted a role in. Refusing puts the
     * choice back where it belongs - transfer first, then close.
     *
     * Ownership of a *soft-deleted* organization does not block. That organization is
     * already closed and unreachable, and requiring a transfer would be an outright dead
     * end, since ownership transfer refuses deleted organizations too.
     */
    @Transactional
    fun closeOwnAccount() {
        // Locked, not read through ActiveUser: this row is the interlock with ownership
        // transfer. A transfer locks the incoming owner's user row before promoting them,
        // so holding it here means no transfer can make this account an owner between the
        // check below and the commit. See UserRepository.findAndLockById.
        val user = userRepository.findAndLockById(currentUser.id())
            ?: throw AccessDeniedException("Account is no longer active")

        if (user.deletedAt != null) {
            // Same answer ActiveUser.require would have given. Closing twice is not a
            // meaningful operation, and the second caller is holding a token for an
            // account that no longer exists.
            throw AccessDeniedException("Account is no longer active")
        }

        val owned = organizationMemberRepository.findOrganizationsForUser(user.id!!)
            .filter { it.role == OrganizationRole.OWNER }

        if (owned.isNotEmpty()) {
            throw AccountClosureBlockedException(owned)
        }

        user.deletedAt = Instant.now()

        // Sessions have to go with the account, in this same transaction. Refresh is not
        // account-aware - RefreshTokenService.rotate mints a new pair from the token alone
        // and never consults the user row - so a live refresh token would otherwise keep
        // producing access tokens for a closed account indefinitely. Those tokens would be
        // refused at every protected endpoint by ActiveUser, but /api/auth/refresh itself
        // would keep answering 200, which is not a closed account in any useful sense.
        refreshTokenRevoker.revokeAllForInCurrentTransaction(user.id!!)

        // Non-owner memberships are deliberately left in place. See the class comment on
        // the decision; in short, the row is already inert and invisible, and removing it
        // would destroy the record of who was in an organization to no benefit.
    }
}
