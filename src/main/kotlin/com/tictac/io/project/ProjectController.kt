package com.tictac.io.project

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
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
 * Projects are addressed under the organization that owns them, so the tenant is present in
 * every path and can always be checked. A top-level `/api/projects/{id}` would make the
 * organization implicit and the check easy to omit - which is the shape cross-tenant leaks
 * take.
 *
 * No authorisation logic here. Both path variables are untrusted and go straight to a
 * service, each of which opens with [ProjectAccess].
 *
 * There is no `DELETE`. Projects archive via `PATCH {"isActive": false}` and are never
 * removed, because time entries will point at them.
 */
@RestController
@RequestMapping("/api/organizations/{organizationId}/projects")
class ProjectController(
    private val projectService: ProjectService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable organizationId: UUID,
        @Valid @RequestBody request: CreateProjectRequest,
    ): ProjectResponse = projectService.create(organizationId, request)

    /**
     * Administrators get the organization's projects; everyone else gets the ones they are
     * assigned to. [active] filters on the archive flag - omit it for everything, `true` for
     * the projects that are selectable for new work.
     */
    @GetMapping
    fun list(
        @PathVariable organizationId: UUID,
        @RequestParam(required = false) active: Boolean?,
    ): List<ProjectResponse> = projectService.list(organizationId, active)

    @GetMapping("/{projectId}")
    fun get(
        @PathVariable organizationId: UUID,
        @PathVariable projectId: UUID,
    ): ProjectResponse = projectService.get(organizationId, projectId)

    @PatchMapping("/{projectId}")
    fun update(
        @PathVariable organizationId: UUID,
        @PathVariable projectId: UUID,
        @Valid @RequestBody request: UpdateProjectRequest,
    ): ProjectResponse = projectService.update(organizationId, projectId, request)
}
