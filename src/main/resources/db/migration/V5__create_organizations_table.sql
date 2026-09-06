-- Organizations: the customer of record. A subscription, and eventually every client,
-- project and time entry, hangs off one of these rather than off a user.
--
-- Column names (organization_id, organization_name) follow the agreed product DBML
-- rather than the id/name shorthand used by `users`. The entity maps them back to
-- `id` and `name`, so the inconsistency stops at the schema boundary.

CREATE TABLE organizations (
    organization_id   UUID         NOT NULL,
    organization_name VARCHAR(200) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    -- Soft delete. Business data is never destroyed by an API call; a deleted
    -- organization simply stops being reachable. Null means active.
    deleted_at        TIMESTAMPTZ,

    CONSTRAINT pk_organizations PRIMARY KEY (organization_id)
);

-- Deliberately no unique index on organization_name: two unrelated customers may both
-- be called "Acme", and a global name constraint would let one tenant discover another.
