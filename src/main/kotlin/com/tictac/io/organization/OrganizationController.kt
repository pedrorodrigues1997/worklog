package com.tictac.io.organization

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * All of these require a valid access token: `/api/organizations` is not in
 * [com.tictac.io.authentication.SecurityConfig.PUBLIC_POST_PATHS], so it falls to the
 * default-deny chain and an anonymous request is rejected before reaching this class.
 *
 * The controllers hold no authorization logic. `organizationId` arrives here as an
 * untrusted path variable and is handed straight to a service, each of which opens with
 * [OrganizationAccess.require]; keeping the check out of the controller is what stops it
 * from being half-copied into the next endpoint someone adds.
 */
@RestController
@RequestMapping("/api/organizations")
class OrganizationController(
    private val organizationService: OrganizationService,
    private val organizationOwnershipService: OrganizationOwnershipService,
) {

    /** The caller becomes the OWNER. There is no way to create one owned by anyone else. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateOrganizationRequest): OrganizationResponse =
        organizationService.create(request)

    /** Only the caller's own organizations - there is no "list all organizations". */
    @GetMapping
    fun listMine(): List<OrganizationResponse> = organizationService.listMine()

    @GetMapping("/{organizationId}")
    fun get(@PathVariable organizationId: UUID): OrganizationResponse =
        organizationService.get(organizationId)

    @PatchMapping("/{organizationId}")
    fun update(
        @PathVariable organizationId: UUID,
        @Valid @RequestBody request: UpdateOrganizationRequest,
    ): OrganizationResponse = organizationService.rename(organizationId, request)

    /**
     * Soft delete, OWNER only. Nothing belonging to the organization is destroyed.
     *
     * Any active Stripe subscription stops renewing. `?cancelImmediately=true` ends it on the
     * spot instead, crediting the unused remainder - the default leaves the period the customer
     * already paid for alone.
     */
    @DeleteMapping("/{organizationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable organizationId: UUID,
        @RequestParam(defaultValue = "false") cancelImmediately: Boolean,
    ) = organizationService.softDelete(organizationId, cancelImmediately)

    /**
     * Hands the organization to another member, demoting the caller to ADMIN. OWNER only.
     *
     * A POST rather than a PATCH on the member: it is a single named operation with two
     * effects, not an edit of one member's role - and the membership API refuses to touch
     * OWNER precisely so that this is the only route to it.
     */
    @PostMapping("/{organizationId}/transfer-ownership")
    fun transferOwnership(
        @PathVariable organizationId: UUID,
        @Valid @RequestBody request: TransferOwnershipRequest,
    ): OwnershipTransferResponse = organizationOwnershipService.transferOwnership(organizationId, request)
}
