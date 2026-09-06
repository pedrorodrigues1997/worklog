-- Time entries: the product. Everything before this table existed to make it possible to
-- say "this person spent this long on this work for this company".
--
-- Column names (time_entry_id) follow the agreed product DBML, as organizations and
-- projects do; the entity maps it back to `id`.
--
-- organization_id is stored even though it is reachable through projects.organization_id.
-- That is a deliberate denormalisation, for two reasons: every read is tenant-scoped and
-- would otherwise need a join to projects before it could even check authorisation, and the
-- one-running-timer index below is per organization and cannot be expressed without the
-- column. The application never derives it from the request - it comes from the project,
-- which has already been resolved inside an authorised organization.

CREATE TABLE time_entries (
    time_entry_id    UUID        NOT NULL,
    organization_id  UUID        NOT NULL,
    project_id       UUID        NOT NULL,
    user_id          UUID        NOT NULL,

    description      TEXT,

    started_at       TIMESTAMPTZ NOT NULL,
    -- Null means the timer is still running. That is the single source of truth for
    -- "is a timer going?" - there is no separate status column that could disagree with it,
    -- and no server-side ticking: elapsed time is computed from started_at on read.
    ended_at         TIMESTAMPTZ,
    -- Null while running, and written once on stop. Derived from the two timestamps and
    -- never accepted from a client; stored rather than computed on every read because
    -- reporting will eventually sum it over very large result sets.
    duration_seconds BIGINT,

    billable         BOOLEAN     NOT NULL DEFAULT FALSE,

    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    -- Soft delete, matching users and organizations. A deleted entry is recoverable in the
    -- database; see the note on the deletion strategy in TimeEntryService.
    deleted_at       TIMESTAMPTZ,

    CONSTRAINT pk_time_entries PRIMARY KEY (time_entry_id),

    -- All three restrict, none cascade. This is the point of the table.
    --
    -- A time entry is historical business data that will eventually be invoiced. It must
    -- survive a person leaving a project, leaving the organization, and having their
    -- account closed - and it must survive a project being archived. Those events all
    -- happen through link tables (project_members, organization_members), which cascade
    -- precisely because they carry no information of their own; this table is never
    -- reachable by those cascades and must never become so.
    --
    -- What restrict adds on top is protection from the remaining path: a hard DELETE of a
    -- user, project or organization. Nothing in the application does that - users
    -- soft-delete, projects archive, organizations soft-delete - so in practice these
    -- constraints only ever fire against a future code path or a hand-written statement,
    -- which is exactly when losing a customer's billable history silently would be worst.
    CONSTRAINT fk_time_entries_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (organization_id),
    CONSTRAINT fk_time_entries_project
        FOREIGN KEY (project_id) REFERENCES projects (project_id),
    CONSTRAINT fk_time_entries_user
        FOREIGN KEY (user_id) REFERENCES users (id),

    -- Cheap structural guarantees that no application bug can talk its way past.
    CONSTRAINT ck_time_entries_ended_after_started
        CHECK (ended_at IS NULL OR ended_at > started_at),
    -- Running means no duration; stopped means one. The two nullable columns can never
    -- drift apart into "stopped but no duration" or "running but 40 minutes elapsed".
    CONSTRAINT ck_time_entries_duration_matches_state
        CHECK ((ended_at IS NULL) = (duration_seconds IS NULL)),
    CONSTRAINT ck_time_entries_duration_not_negative
        CHECK (duration_seconds IS NULL OR duration_seconds >= 0)
);

-- One running timer per user per organization, enforced where nothing can bypass it.
--
-- The application checks first so the common case is a clean 409, but that check loses to a
-- concurrent request: two transactions can both see "no timer running" and both insert.
-- This index is the actual guarantee. Same shape as the one-OWNER-per-organization index.
--
-- Soft-deleted rows are excluded, or deleting a running timer would leave its slot occupied
-- forever.
CREATE UNIQUE INDEX ux_time_entries_running_per_user_organization
    ON time_entries (organization_id, user_id)
    WHERE ended_at IS NULL AND deleted_at IS NULL;

-- Three read paths, three indexes, all led by organization_id because every query is
-- tenant-scoped before it is anything else - and all trailing started_at DESC, which is
-- both the default ordering and the column the `from`/`to` filters range over.
--
-- There is deliberately no standalone index on started_at: no query looks across tenants,
-- so it could never be the right choice.

-- An administrator's unfiltered listing of the organization.
CREATE INDEX ix_time_entries_organization_started_at
    ON time_entries (organization_id, started_at DESC);

-- The hottest query in the product: a member listing their own entries, which happens on
-- every page load. Also serves an administrator filtering by user.
CREATE INDEX ix_time_entries_organization_user_started_at
    ON time_entries (organization_id, user_id, started_at DESC);

-- Filtering by project, and the query reporting will eventually build on.
CREATE INDEX ix_time_entries_organization_project_started_at
    ON time_entries (organization_id, project_id, started_at DESC);
