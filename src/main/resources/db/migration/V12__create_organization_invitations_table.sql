-- Organization invitations: how a real company onboards its people without anyone writing
-- organization_members rows by hand.
--
-- Shaped like the other two token tables (refresh_tokens, oauth_login_codes) because it is
-- the same kind of thing: a short-lived bearer secret, stored only as a hash, single use,
-- with an expiry.

CREATE TABLE organization_invitations (
    id                 UUID         NOT NULL,
    organization_id    UUID         NOT NULL,
    -- Stored normalised (trimmed + lowercased) by the application, exactly as users.email
    -- is - otherwise an invitation to Bob@example.com could never be matched against the
    -- account bob@example.com.
    email              VARCHAR(320) NOT NULL,
    invited_by_user_id UUID         NOT NULL,
    -- SHA-256 hex of the invitation token. The token itself is never stored, so a database
    -- dump contains no usable invitations. SHA-256 rather than Argon2 for the same reason
    -- refresh tokens use it: this is a 256-bit CSPRNG value, not a human-chosen password,
    -- so there is nothing to brute-force and a slow hash would only make acceptance slow.
    token_hash         VARCHAR(64)  NOT NULL,

    created_at         TIMESTAMPTZ  NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    -- Null until accepted. There is deliberately no status column: pending, expired and
    -- accepted are all derivable from these two timestamps, and a fourth representation of
    -- the same fact is a fourth thing that can disagree with the others.
    --
    --   pending  = accepted_at IS NULL AND expires_at >  now()
    --   expired  = accepted_at IS NULL AND expires_at <= now()
    --   accepted = accepted_at IS NOT NULL
    accepted_at        TIMESTAMPTZ,

    CONSTRAINT pk_organization_invitations PRIMARY KEY (id),

    -- Cascades, unlike the data tables. An invitation is ephemeral onboarding state that
    -- means nothing without its organization - the same call refresh_tokens and
    -- oauth_login_codes make about their user.
    CONSTRAINT fk_organization_invitations_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (organization_id) ON DELETE CASCADE,
    -- Restricts, matching project_categories.created_by_user_id: this is provenance, and a
    -- hard delete of the inviter should fail loudly rather than quietly erase who invited
    -- whom. Nothing in the application hard-deletes a user anyway.
    CONSTRAINT fk_organization_invitations_invited_by
        FOREIGN KEY (invited_by_user_id) REFERENCES users (id),

    CONSTRAINT ck_organization_invitations_expires_after_created
        CHECK (expires_at > created_at)
);

-- One invitation per token. Also the lookup path for acceptance, which is by hash only.
CREATE UNIQUE INDEX ux_organization_invitations_token_hash
    ON organization_invitations (token_hash);

-- At most one *un-accepted* invitation per organization and address.
--
-- "Pending" cannot be expressed in an index predicate - now() is not immutable - so the
-- index covers the wider "not accepted" set, and the application deletes an expired
-- invitation before issuing its replacement. That keeps re-inviting someone possible after
-- an invitation lapses while still making two live invitations to the same address
-- impossible, whatever two concurrent requests do.
--
-- Accepted rows are excluded, so the same person can be invited, accepted, removed from the
-- organization and invited again, with each acceptance left in place as history.
CREATE UNIQUE INDEX ux_organization_invitations_open
    ON organization_invitations (organization_id, email)
    WHERE accepted_at IS NULL;
