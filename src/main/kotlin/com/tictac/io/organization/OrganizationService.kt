package com.tictac.io.organization

import com.tictac.io.user.ActiveUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class OrganizationService(
    private val activeUser: ActiveUser,
    private val organizationRepository: OrganizationRepository,
    private val organizationMemberRepository: OrganizationMemberRepository,
    private val organizationAccess: OrganizationAccess,
) {

    /**
     * Creates an organization and makes the caller its owner.
     *
     * The two writes are one transaction, and that is the point rather than a detail. An
     * organization with no owner is unreachable and unfixable through the API - nobody can
     * be granted a role in it, because granting roles requires being a member of it - so a
     * half-applied create would leave a permanently orphaned tenant. The reverse, a
     * membership pointing at no organization, is prevented by the foreign key regardless.
     *
     * The owner is [ActiveUser.require], never a user id from the body. A request cannot
     * create an organization owned by somebody else.
     */
    @Transactional
    fun create(request: CreateOrganizationRequest): OrganizationResponse {
        // Safe: the controller validates with @Valid, so the name is present and non-blank.
        val creator = activeUser.require()

        // saveAndFlush on both, not save: it pins the order the two INSERTs reach the
        // database, so the membership's unique indexes - including the one-OWNER-per-
        // organization index - are enforced inside this method rather than at commit,
        // and a failure on the second statement rolls the first one back for real.
        val organization = organizationRepository.saveAndFlush(Organization(name = request.name!!.trim()))

        organizationMemberRepository.saveAndFlush(
            OrganizationMember(
                organizationId = organization.id!!,
                userId = creator.id!!,
                role = OrganizationRole.OWNER,
            ),
        )

        return OrganizationResponse(
            id = organization.id!!,
            name = organization.name,
            role = OrganizationRole.OWNER,
            createdAt = organization.createdAt,
        )
    }

    /**
     * The organizations the caller belongs to, with their role in each. A user with no
     * memberships gets an empty list - never "all organizations", which is what a missing
     * tenant filter usually looks like.
     */
    @Transactional(readOnly = true)
    fun listMine(): List<OrganizationResponse> =
        organizationMemberRepository.findOrganizationsForUser(activeUser.require().id!!)

    /** Readable by any member. */
    @Transactional(readOnly = true)
    fun get(organizationId: UUID): OrganizationResponse =
        organizationAccess.require(organizationId).toResponse()

    /** Organization settings are administrative: OWNER and ADMIN only. */
    @Transactional
    fun rename(organizationId: UUID, request: UpdateOrganizationRequest): OrganizationResponse {
        val context = organizationAccess.require(organizationId, OrganizationRole.OWNER, OrganizationRole.ADMIN)

        // Managed entity inside this transaction, so the update is flushed at commit.
        context.organization.name = request.name!!.trim()

        return context.toResponse()
    }

    /**
     * Soft delete. OWNER only, and closing the organization is all it does.
     *
     * Nothing cascades: memberships stay, and the clients, projects and time entries that
     * will eventually hang off an organization stay too. Destroying a customer's data on
     * one API call is not recoverable, and the row-level tombstone is enough to make the
     * organization unreachable - [OrganizationAccess] refuses it, and it drops out of
     * every listing. Purging, and restoring, are separate deliberate operations.
     */
    @Transactional
    fun softDelete(organizationId: UUID) {
        val context = organizationAccess.require(organizationId, OrganizationRole.OWNER)

        if (context.organization.deletedAt == null) {
            context.organization.deletedAt = Instant.now()
        }
    }

    private fun OrganizationContext.toResponse() =
        OrganizationResponse(
            id = organizationId,
            name = organization.name,
            role = role,
            createdAt = organization.createdAt,
        )
}
