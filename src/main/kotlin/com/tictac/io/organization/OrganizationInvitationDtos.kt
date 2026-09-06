package com.tictac.io.organization

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * The address to invite. Validated exactly as [com.tictac.io.authentication.RegisterRequest]
 * validates its own - same annotations, same limits, same 400 - because an invitation that
 * accepted addresses registration would refuse could never be accepted by anybody.
 *
 * Note what is absent: a role. Every invitation grants MEMBER. Letting the inviter choose
 * would mean an ADMIN could mint another ADMIN - or an OWNER - through a side door that
 * bypasses the rank rules the membership API enforces so carefully.
 */
data class CreateInvitationRequest(
    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Email must be a valid email address")
    @field:Size(max = 320, message = "Email must be at most 320 characters")
    val email: String?,
)

/**
 * The invitation as created.
 *
 * [token] is the raw invitation token, and it appears here **only because email delivery
 * does not exist yet**. It is the one moment the raw value exists outside the database, and
 * once a mail provider is wired up this field goes away and the token goes to the invitee
 * instead. Nothing in the system persists or logs it.
 */
data class InvitationResponse(
    val id: UUID,
    val organizationId: UUID,
    val email: String,
    val invitedByUserId: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val token: String,
)

/**
 * Acceptance by someone who already has an account.
 *
 * The token is the only field, deliberately. Who is accepting comes from the access token,
 * and which organization they join comes from the invitation - neither is a value the client
 * gets to supply, so neither is a value the client can tamper with.
 */
data class AcceptInvitationRequest(
    @field:NotBlank(message = "Invitation token is required")
    val token: String?,
)

/**
 * Acceptance by someone who does not have an account yet: the invitation token *plus* the
 * credentials registration has always required.
 *
 * The token proves possession of the invitation. It does not stand in for creating an
 * account, which is why every field [com.tictac.io.authentication.RegisterRequest] asks for
 * is asked for here too.
 */
data class RegisterWithInvitationRequest(
    @field:NotBlank(message = "Invitation token is required")
    val token: String?,

    @field:NotBlank(message = "First name is required")
    @field:Size(max = 100, message = "First name must be at most 100 characters")
    val firstName: String?,

    @field:NotBlank(message = "Last name is required")
    @field:Size(max = 100, message = "Last name must be at most 100 characters")
    val lastName: String?,

    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Email must be a valid email address")
    @field:Size(max = 320, message = "Email must be at most 320 characters")
    val email: String?,

    @field:NotBlank(message = "Password is required")
    @field:Size(
        min = com.tictac.io.authentication.RegisterRequest.MIN_PASSWORD_LENGTH,
        max = com.tictac.io.authentication.RegisterRequest.MAX_PASSWORD_LENGTH,
        message = "Password must be between 12 and 128 characters",
    )
    val password: String?,
)

/**
 * What the invitee joined. Returned so a client can switch straight into the organization
 * without a follow-up read; [role] is always MEMBER.
 */
data class AcceptedInvitationResponse(
    val organizationId: UUID,
    val organizationName: String,
    val userId: UUID,
    val role: OrganizationRole,
    val acceptedAt: Instant,
)
