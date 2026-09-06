package com.tictac.io.organization

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Members are addressed by their user id under the organization that owns the membership,
 * so the tenant is always present in the path and can always be checked. A top-level
 * `/api/members/{id}` would make the organization implicit and the check easy to omit.
 *
 * `userId` here identifies the member being *acted upon*. It never identifies the caller -
 * that comes from the access token - so supplying someone else's id grants nothing.
 */
@RestController
@RequestMapping("/api/organizations/{organizationId}/members")
class OrganizationMemberController(
    private val organizationMembershipService: OrganizationMembershipService,
) {

    @GetMapping
    fun list(@PathVariable organizationId: UUID): List<OrganizationMemberResponse> =
        organizationMembershipService.listMembers(organizationId)

    @PatchMapping("/{userId}")
    fun changeRole(
        @PathVariable organizationId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: ChangeMemberRoleRequest,
    ): OrganizationMemberResponse =
        organizationMembershipService.changeRole(organizationId, userId, request)

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable organizationId: UUID, @PathVariable userId: UUID) =
        organizationMembershipService.removeMember(organizationId, userId)
}
