package com.tictac.io.project

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Assignments live under the project, which lives under the organization, so both tenancy
 * levels are in the path and both are checked on every call.
 *
 * `userId` identifies the person being assigned or unassigned. It never identifies the
 * caller - that comes from the access token - so supplying someone else's id grants nothing.
 */
@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/members")
class ProjectMemberController(
    private val projectMembershipService: ProjectMembershipService,
) {

    @GetMapping
    fun list(
        @PathVariable organizationId: UUID,
        @PathVariable projectId: UUID,
    ): List<ProjectMemberResponse> = projectMembershipService.listMembers(organizationId, projectId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @PathVariable organizationId: UUID,
        @PathVariable projectId: UUID,
        @Valid @RequestBody request: AddProjectMemberRequest,
    ): ProjectMemberResponse = projectMembershipService.addMember(organizationId, projectId, request)

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @PathVariable organizationId: UUID,
        @PathVariable projectId: UUID,
        @PathVariable userId: UUID,
    ) = projectMembershipService.removeMember(organizationId, projectId, userId)
}
