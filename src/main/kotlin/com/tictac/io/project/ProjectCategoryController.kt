package com.tictac.io.project

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
 * Categories are addressed under their project, which is addressed under its organization,
 * so both tenancy levels are in the path and both are checked on every call. A category id
 * on its own is never enough to reach one.
 *
 * No authorisation logic here - all three path variables are untrusted and go straight to the
 * service, which opens with [ProjectAccess].
 *
 * There is no `DELETE`. A category is retired with `PATCH {"isActive": false}` and kept,
 * because time entries point at it and must keep resolving.
 */
@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/categories")
class ProjectCategoryController(
    private val projectCategoryService: ProjectCategoryService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable organizationId: UUID,
        @PathVariable projectId: UUID,
        @RequestBody request: CreateProjectCategoryRequest,
    ): ProjectCategoryResponse = projectCategoryService.create(organizationId, projectId, request)

    /**
     * Active categories, ordered by name - the list a user picks from when tracking time.
     *
     * [includeInactive] adds the retired ones, for administrators. It is ignored rather than
     * refused for anyone else: see [ProjectCategoryService.list].
     */
    @GetMapping
    fun list(
        @PathVariable organizationId: UUID,
        @PathVariable projectId: UUID,
        @RequestParam(defaultValue = "false") includeInactive: Boolean,
    ): List<ProjectCategoryResponse> =
        projectCategoryService.list(organizationId, projectId, includeInactive)

    @GetMapping("/{categoryId}")
    fun get(
        @PathVariable organizationId: UUID,
        @PathVariable projectId: UUID,
        @PathVariable categoryId: UUID,
    ): ProjectCategoryResponse = projectCategoryService.get(organizationId, projectId, categoryId)

    @PatchMapping("/{categoryId}")
    fun update(
        @PathVariable organizationId: UUID,
        @PathVariable projectId: UUID,
        @PathVariable categoryId: UUID,
        @RequestBody request: UpdateProjectCategoryRequest,
    ): ProjectCategoryResponse =
        projectCategoryService.update(organizationId, projectId, categoryId, request)
}
