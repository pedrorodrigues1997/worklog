-- Refresh-token state, kept server-side so tokens can be revoked.
--
-- Only the SHA-256 of each token is stored: a leaked database dump therefore contains
-- no usable refresh tokens. SHA-256 rather than Argon2 is deliberate - these are 256-bit
-- values from a CSPRNG, not human-chosen passwords, so there is nothing to brute-force
-- and a slow hash would only make every refresh expensive.

CREATE TABLE refresh_tokens (
    id             UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    token_hash     VARCHAR(64) NOT NULL,
    issued_at      TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    -- Set when the token is rotated away, revoked at logout, or revoked as part of a
    -- reuse response. A token is usable only while this is null and expires_at is future.
    revoked_at     TIMESTAMPTZ,
    -- The token issued in this one's place, so a rotation chain can be walked.
    replaced_by_id UUID,

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by_id) REFERENCES refresh_tokens (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX ux_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
