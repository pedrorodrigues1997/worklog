-- Time entries gain the two fields that make them the product's user-facing object: what
-- the work was called, and which kind of work it was.

-- --- title ------------------------------------------------------------------------------
--
-- Mandatory, but added in three steps because existing rows have none. Adding a NOT NULL
-- column with a DEFAULT would work too and would leave every historical row silently
-- claiming a title somebody chose; backfilling explicitly makes it obvious in the data that
-- these predate the field.
ALTER TABLE time_entries ADD COLUMN title VARCHAR(255);

UPDATE time_entries SET title = 'Untitled' WHERE title IS NULL;

ALTER TABLE time_entries ALTER COLUMN title SET NOT NULL;

-- --- category ---------------------------------------------------------------------------
--
-- Nullable on purpose, and that is a product decision rather than a convenience: a project
-- with no categories is valid, and tracking straight against a project must stay possible.
ALTER TABLE time_entries ADD COLUMN project_category_id UUID;

-- The project/category consistency invariant, enforced by the database.
--
-- A composite foreign key onto (project_category_id, project_id) means an entry can only
-- name a category that belongs to *its own* project. Under the default MATCH SIMPLE rule the
-- constraint is skipped entirely when project_category_id is null, which is exactly the
-- behaviour a nullable category needs - so "no category" stays free while "some other
-- project's category" is impossible.
--
-- This subsumes a plain foreign key to project_categories: it guarantees the category exists
-- *and* that it is the right one, so a second single-column constraint would only repeat
-- half of it.
--
-- It also means moving an entry to another project fails unless its category moves with it
-- or is cleared - the application does that check first, so the constraint is the backstop.
ALTER TABLE time_entries
    ADD CONSTRAINT fk_time_entries_project_category
        FOREIGN KEY (project_category_id, project_id)
        REFERENCES project_categories (project_category_id, project_id);

-- Filtering a tenant's entries by category. Partial because a category is optional and many
-- entries will not have one: there is no query that looks for entries *without* a category,
-- so indexing those rows would be paying for nothing. Trails started_at DESC like the other
-- three, which is both the default ordering and the column from/to range over.
CREATE INDEX ix_time_entries_organization_category_started_at
    ON time_entries (organization_id, project_category_id, started_at DESC)
    WHERE project_category_id IS NOT NULL;
