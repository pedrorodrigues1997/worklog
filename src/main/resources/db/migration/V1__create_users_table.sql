-- Initial schema: users.
-- Flyway is the single source of truth for the schema; Hibernate only validates it.

CREATE TABLE users (
    id            UUID         NOT NULL,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(320) NOT NULL,
    -- Nullable: a user created through an external identity provider (added later)
    -- legitimately has no local password.
    password_hash VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL,
    deleted_at    TIMESTAMPTZ,

    CONSTRAINT pk_users PRIMARY KEY (id)
);

-- Email is stored already normalised (trimmed + lowercased) by the application,
-- so a plain unique index is enough to make duplicates impossible at the database level.
CREATE UNIQUE INDEX ux_users_email ON users (email);
