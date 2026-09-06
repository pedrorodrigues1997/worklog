package com.tictac.io.organization

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Request and response shapes for the organization API. Entities are never serialised
 * directly - `User` alone would put a password hash on the wire.
 *
 * Request fields are nullable for the same reason as [com.tictac.io.authentication.RegisterRequest]:
 * a missing JSON property on a non-null Kotlin type fails inside Jackson and produces an
 * opaque error, where a nullable field lets Bean Validation report a proper per-field message.
 *
 * Note that no request here carries a user id for the *caller*. Who is acting is settled by
 * the access token; the ids that do appear identify the organization or the member being
 * acted upon, and both are checked against the caller's membership before use.
 */
data class CreateOrganizationRequest(
    @field:NotBlank(message = "Organization name is required")
    @field:Size(max = Organization.MAX_NAME_LENGTH, message = "Organization name must be at most 200 characters")
    val name: String?,
)

/**
 * PATCH body. Only the name is editable today; the field is required because a patch that
 * changes nothing is a client bug worth reporting rather than a silent no-op. When a second
 * editable field appears, this becomes a genuine partial update and the constraint moves.
 */
data class UpdateOrganizationRequest(
    @field:NotBlank(message = "Organization name is required")
    @field:Size(max = Organization.MAX_NAME_LENGTH, message = "Organization name must be at most 200 characters")
    val name: String?,
)

data class ChangeMemberRoleRequest(
    @field:NotNull(message = "Role is required")
    val role: OrganizationRole?,
)

/**
 * The incoming owner, identified by user id and required to be a member already.
 *
 * Deliberately not by email. An email would let a caller test which addresses have
 * accounts, and it would blur the rule that ownership moves *within* an organization -
 * bringing a new person in is an invitation, which is a different operation.
 */
data class TransferOwnershipRequest(
    @field:NotNull(message = "Target user id is required")
    val userId: UUID?,
)

/**
 * Both sides of a completed transfer, so a client knows the whole outcome without a
 * follow-up read - including its own new role, since the caller has just demoted itself.
 */
data class OwnershipTransferResponse(
    val organizationId: UUID,
    val previousOwner: OrganizationMemberResponse,
    val newOwner: OrganizationMemberResponse,
)

/**
 * An organization as seen *by one caller* - [role] is the requesting user's role in it,
 * not a property of the organization. That is why there is no endpoint returning an
 * organization without a membership behind it.
 */
data class OrganizationResponse(
    val id: UUID,
    val name: String,
    val role: OrganizationRole,
    val createdAt: Instant,
)

/** Safe projection of a member. Must never gain a password or password-hash field. */
data class OrganizationMemberResponse(
    val userId: UUID,
    val firstName: String,
    val lastName: String,
    val email: String,
    val role: OrganizationRole,
    val joinedAt: Instant,
)
