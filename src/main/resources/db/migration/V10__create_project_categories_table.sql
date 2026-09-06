-- Project categories: the selectable kinds of work within one project.
--
-- Subdivisions of a project, not entities in their own right. "Development" in one project
-- and "Development" in another are two unrelated rows, because a category only ever means
-- something relative to the project that defines it. There is no global category list.
--
-- Deliberately no organization_id. A category inherits its tenant through
-- projects.organization_id, and a second copy of that answer is a second thing that can
-- disagree with the first. Every read reaches a category through its project, which has
-- already been resolved inside an authorised organization, so the column would buy nothing.

CREATE TABLE project_categories (
    project_category_id UUID         NOT NULL,
    project_id          UUID         NOT NULL,
    -- Kept for provenance: who configured this. Not an owner and not an authorisation
    -- input - what someone may do to a category comes from their organisation role.
    created_by_user_id  UUID         NOT NULL,

    name                VARCHAR(100) NOT NULL,
    description         TEXT,
    -- Same archive flag as projects, and for the same reason: time entries point here, so a
    -- category is retired rather than removed. Inactive means "not selectable for new work".
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_project_categories PRIMARY KEY (project_category_id),

    -- Both restrict, neither cascades, following the rule the rest of the schema uses:
    -- link tables cascade, data tables restrict. A category is configuration a customer's
    -- historical time entries refer to by id, so a hard delete of its project or its author
    -- must fail loudly rather than quietly orphan a timesheet. Nothing in the application
    -- hard-deletes either - projects archive and users soft-delete.
    CONSTRAINT fk_project_categories_project
        FOREIGN KEY (project_id) REFERENCES projects (project_id),
    CONSTRAINT fk_project_categories_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES users (id),

    -- The target of the composite foreign key added to time_entries in V11. Redundant with
    -- the primary key on its own; it exists so that (category, project) can be referenced
    -- as a pair, which is what makes "this entry's category belongs to this entry's
    -- project" a database guarantee rather than an application promise.
    CONSTRAINT ux_project_categories_id_project UNIQUE (project_category_id, project_id)
);

-- One category per name per project, case-insensitively: "Development" and "development"
-- are the same category to a person choosing from a dropdown, so they are the same category
-- here. A functional index on lower(name) is what makes that a constraint rather than a
-- convention no concurrent request has to respect.
--
-- Inactive categories still occupy their name. Reactivating the old "Meetings" is the way
-- back, not creating a second one nobody can tell apart in a report.
--
-- This also serves lookups by project_id, since it leads with that column.
CREATE UNIQUE INDEX ux_project_categories_project_name
    ON project_categories (project_id, lower(name));
