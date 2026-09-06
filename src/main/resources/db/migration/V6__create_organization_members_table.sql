-- Membership: the join between a user and an organization, carrying the role.
--
-- This table - not a column on `users` - is the whole reason a user can belong to several
-- organizations at once with a different role in each. Nothing about the tenant a request
-- operates on may ever be read off the user row.
--
-- It is also the authorization table: every organization-scoped request resolves
-- (organization_id, user_id) here, and a missing row means the caller is not a tenant of
-- that organization. See OrganizationAccess.
--
-- Invitations are not implemented yet. When they are, they get their own table and the
-- accept step inserts here; nothing in this shape needs to change for that.

CREATE TABLE organization_members (
    id              UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    -- OrganizationRole, stored as its name. Kept as text rather than a Postgres enum so
    -- adding a role is an application change, not a schema migration with a type rewrite.
    role            VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_organization_members PRIMARY KEY (id),
    CONSTRAINT fk_organization_members_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_organization_members_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- A user belongs to an organization once, or not at all. Two rows would mean two roles
-- for the same person and an authorization answer that depends on row order.
CREATE UNIQUE INDEX ux_organization_members_organization_user
    ON organization_members (organization_id, user_id);

-- Exactly one OWNER per organization, enforced where it cannot be bypassed.
-- The application refuses to assign or remove OWNER at all (ownership transfer is a
-- separate, deliberate operation that does not exist yet); this index means no future
-- code path - or manual fix-up - can quietly produce a second owner either.
CREATE UNIQUE INDEX ux_organization_members_single_owner
    ON organization_members (organization_id)
    WHERE role = 'OWNER';

-- Drives "list the organizations I belong to", which runs on every frontend page load.
CREATE INDEX ix_organization_members_user_id ON organization_members (user_id);
