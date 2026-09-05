-- External identities attached to an internal user. Google today; the table is keyed by
-- provider so a second one needs no schema change.
--
-- A separate table rather than columns on `users` because the relationship is one-to-many:
-- one account can carry a password *and* one or more external identities. Provider columns
-- on `users` would mean a schema change for every provider added, and a wide row of nulls.
--
-- users.id stays the only identity the rest of the application knows about. A provider's
-- subject never becomes a primary key; it is only ever a lookup key into this table.

CREATE TABLE user_identities (
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    provider         VARCHAR(32)  NOT NULL,
    -- The provider's stable subject identifier ("sub"), not an email. Emails change hands;
    -- the subject does not, which is why it - and not the address - decides who this is.
    provider_user_id VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_user_identities PRIMARY KEY (id),
    CONSTRAINT fk_user_identities_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- One internal account per external identity. This is what stops two of our users both
-- claiming the same Google account.
CREATE UNIQUE INDEX ux_user_identities_provider_subject
    ON user_identities (provider, provider_user_id);

-- ...and one identity per provider per user, so an account cannot silently accumulate
-- several Google logins that nobody can tell apart.
CREATE UNIQUE INDEX ux_user_identities_user_provider
    ON user_identities (user_id, provider);
