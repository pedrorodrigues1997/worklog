-- Projects: the unit of work a time entry will eventually be recorded against.
--
-- Column names (project_id, project_name) follow the agreed product DBML, the same way
-- organizations does; the entity maps them back to id/name.
--
-- organization_id is NOT NULL and has no default. A project without an organization would
-- be a row no tenant check could ever reach - it is the tenancy anchor for everything that
-- will hang off a project later, so it is required at the schema level and never taken
-- from a request body.

CREATE TABLE projects (
    project_id      UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    project_name    VARCHAR(200) NOT NULL,
    description     TEXT,
    -- Archive flag rather than a deleted_at. Projects are never removed: time entries will
    -- point at them, and an inactive project still has to render every historical entry
    -- that references it. Inactive means "not selectable for new work", not "gone".
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_projects PRIMARY KEY (project_id),
    -- Deliberately NOT "ON DELETE CASCADE", unlike the link tables.
    --
    -- organization_members cascades because a membership row carries no information of its
    -- own. A project does: it is the parent of the time entries a business bills against.
    -- Restricting means a hard DELETE of an organization fails loudly while projects exist,
    -- rather than silently taking a customer's entire history with it. Nothing in the
    -- application hard-deletes an organization anyway - it soft-deletes - so this is a
    -- backstop against a future code path or a hand-written statement, which is precisely
    -- when the loud failure is worth having.
    CONSTRAINT fk_projects_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (organization_id)
);

-- Every project query is scoped by organization first - that is the tenancy check - so
-- this index carries the listing endpoint and the membership lookups underneath it.
CREATE INDEX ix_projects_organization_id ON projects (organization_id);

-- Deliberately no unique index on (organization_id, project_name): archiving "Website
-- Redesign" and starting a new one next year under the same name is normal, and the two
-- rows must be able to coexist.
