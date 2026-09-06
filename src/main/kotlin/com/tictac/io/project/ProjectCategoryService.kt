package com.tictac.io.project

import com.tictac.io.organization.OrganizationRole
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Categories are project *configuration*, which is what decides who may touch them.
 *
 * OWNER and ADMIN configure; a MEMBER reads the list and chooses from it when tracking time.
 * That is the same split as project settings and needs no second authorisation system - every
 * method here goes through [ProjectAccess], and the role gate is passed to it rather than
 * re-derived.
 */
@Service
class ProjectCategoryService(
    private val projectAccess: ProjectAccess,
    private val projectCategoryRepository: ProjectCategoryRepository,
) {

    /**
     * Creates a category in a project. OWNER and ADMIN only.
     *
     * The project must be active: a category is a choice offered for *new* work, and offering
     * new choices on an archived project is configuring something nobody can use.
     *
     * Duplicate names are checked here for a clean 409 and guaranteed by the functional
     * unique index on `(project_id, lower(name))`. Two concurrent creates both pass the
     * check; only one passes the index, and the loser is translated into the same conflict.
     */
    @Transactional
    fun create(
        organizationId: UUID,
        projectId: UUID,
        request: CreateProjectCategoryRequest,
    ): ProjectCategoryResponse {
        val context = projectAccess.require(
            organizationId,
            projectId,
            OrganizationRole.OWNER,
            OrganizationRole.ADMIN,
        )

        if (!context.project.isActive) {
            throw ProjectNotActiveException()
        }

        val name = validName(request.name)
        val description = validDescription(request.description)

        if (projectCategoryRepository.findByProjectIdAndNameIgnoringCase(context.projectId, name) != null) {
            throw DuplicateProjectCategoryNameException()
        }

        val category = ProjectCategory(
            projectId = context.projectId,
            createdByUserId = context.userId,
            name = name,
            description = description,
        )

        val saved = try {
            // saveAndFlush so the unique index is evaluated inside this method rather than
            // at commit, where the violation would surface as a 500.
            projectCategoryRepository.saveAndFlush(category)
        } catch (ex: DataIntegrityViolationException) {
            throw DuplicateProjectCategoryNameException()
        }

        return saved.toResponse()
    }

    /**
     * The project's categories, ordered by name.
     *
     * Active only by default, because the ordinary use of this endpoint is populating the
     * picker a user chooses from when tracking time. [includeInactive] lifts that for
     * administrators, who need to see the archive to bring something back.
     *
     * **A MEMBER cannot use [includeInactive] to see more than they should.** The flag is
     * ignored for them rather than refused: they are asking a question about configuration
     * they do not administer, and the honest answer is the active list they were always
     * going to get. Nothing about their access is widened by asking.
     */
    @Transactional(readOnly = true)
    fun list(
        organizationId: UUID,
        projectId: UUID,
        includeInactive: Boolean = false,
    ): List<ProjectCategoryResponse> {
        val context = projectAccess.require(organizationId, projectId)

        val categories = if (includeInactive && context.organizationRole.administersProjects()) {
            projectCategoryRepository.findAllByProjectIdOrderByNameAscIdAsc(context.projectId)
        } else {
            projectCategoryRepository.findAllByProjectIdAndIsActiveOrderByNameAscIdAsc(context.projectId, true)
        }

        return categories.map { it.toResponse() }
    }

    /** Readable by anyone who can see the project - administrators, and assigned members. */
    @Transactional(readOnly = true)
    fun get(organizationId: UUID, projectId: UUID, categoryId: UUID): ProjectCategoryResponse =
        projectAccess.requireCategory(organizationId, projectId, categoryId).category.toResponse()

    /**
     * Renames, re-describes, retires or restores a category. OWNER and ADMIN only.
     *
     * The project is not editable and is not in the request: moving a category between
     * projects would silently change which project's work every entry naming it describes.
     *
     * Retiring is an ordinary update rather than its own endpoint, and there is no `DELETE`
     * at all. Time entries point at categories, so a category is retired and kept - the
     * historical entries that name it must keep resolving, and `is_active` says exactly what
     * a delete endpoint would have had to say anyway.
     */
    @Transactional
    fun update(
        organizationId: UUID,
        projectId: UUID,
        categoryId: UUID,
        request: UpdateProjectCategoryRequest,
    ): ProjectCategoryResponse {
        val context = projectAccess.requireCategory(
            organizationId,
            projectId,
            categoryId,
            OrganizationRole.OWNER,
            OrganizationRole.ADMIN,
        )
        val category = context.category

        request.name?.let { requested ->
            val name = validName(requested)

            // Only a *different* category holding the name is a conflict; renaming to a
            // different capitalisation of the current name is not.
            val clash = projectCategoryRepository.findByProjectIdAndNameIgnoringCase(context.projectId, name)
            if (clash != null && clash.id != category.id) {
                throw DuplicateProjectCategoryNameException()
            }
            category.name = name
        }
        request.description?.let { category.description = validDescription(it) }
        request.isActive?.let { category.isActive = it }

        return try {
            // Managed entity, so the update flushes at commit - but flush now so a lost race
            // on the unique index becomes a 409 here rather than a 500 later.
            projectCategoryRepository.saveAndFlush(category).toResponse()
        } catch (ex: DataIntegrityViolationException) {
            throw DuplicateProjectCategoryNameException()
        }
    }

    /**
     * Trimmed before it is judged: a name of three spaces is not a name, and storing it
     * would put an invisible entry in everybody's picker.
     */
    private fun validName(raw: String?): String {
        val name = raw?.trim().orEmpty()

        if (name.isEmpty()) {
            throw ProjectCategoryValidationException("Category name is required")
        }
        if (name.length > ProjectCategory.MAX_NAME_LENGTH) {
            throw ProjectCategoryValidationException(
                "Category name must be at most ${ProjectCategory.MAX_NAME_LENGTH} characters",
            )
        }

        return name
    }

    /** Blank becomes null: "no description" has one representation, not two. */
    private fun validDescription(raw: String?): String? {
        val description = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        if (description.length > ProjectCategory.MAX_DESCRIPTION_LENGTH) {
            throw ProjectCategoryValidationException(
                "Category description must be at most ${ProjectCategory.MAX_DESCRIPTION_LENGTH} characters",
            )
        }

        return description
    }
}

internal fun ProjectCategory.toResponse() =
    ProjectCategoryResponse(
        id = id!!,
        projectId = projectId,
        name = name,
        description = description,
        isActive = isActive,
        createdByUserId = createdByUserId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
