-- Project assignment: which organization members work on which project.
--
-- A second, narrower membership than organization_members, and the distinction is the
-- point. Organization membership answers "does this person belong to the company?";
-- this answers "is this person assigned to this piece of work?". Belonging to the company
-- deliberately grants nothing here - assignment is always explicit.
--
-- The invariant "a project member is also an organization member" cannot be expressed as a
-- foreign key: this table references users and projects, and the organization sits one hop
-- away through projects.organization_id. It is therefore enforced in the application, on
-- both sides - assignment checks organization membership before inserting, and removing
-- someone from an organization deletes their assignments in that organization in the same
-- transaction. See ProjectMembershipService and OrganizationMembershipService.removeMember.

CREATE TABLE project_members (
    id         UUID        NOT NULL,
    project_id UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_project_members PRIMARY KEY (id),
    -- Both cascade, unlike projects.organization_id above: an assignment row is a pure
    -- link and means nothing once either end is gone. Neither parent is ever hard-deleted
    -- by the application (projects archive, users soft-delete), so this only decides what
    -- happens if one ever is.
    CONSTRAINT fk_project_members_project
        FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE CASCADE,
    CONSTRAINT fk_project_members_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- A user is assigned to a project once, or not at all. Application code checks for a
-- duplicate first so the common case is a clean 409, but this is the actual guarantee:
-- two concurrent assignments can both pass that check.
CREATE UNIQUE INDEX ux_project_members_project_user
    ON project_members (project_id, user_id);

-- Drives "which projects am I assigned to" - the member's project listing, and the query
-- time tracking will need to answer "what can I track against?" - and the bulk delete when
-- someone leaves an organization.
CREATE INDEX ix_project_members_user_id ON project_members (user_id);
