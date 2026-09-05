-- Single-use codes handing an OAuth sign-in back to the frontend.
--
-- The provider redirects to the backend, but the tokens have to reach a separate SPA. The
-- obvious route - putting the access and refresh tokens in the redirect URL - writes a
-- 30-day credential into browser history and into any log that records the landing URL.
-- Instead the redirect carries a code that is single-use and expires in seconds, which the
-- frontend exchanges for the normal token pair over POST. Same shape as the authorization
-- code we just consumed from the provider, and for the same reason.
--
-- Hashed at rest for the same reason refresh tokens are: a database dump yields nothing
-- usable. SHA-256 rather than Argon2 because these are CSPRNG values, not passwords.

CREATE TABLE oauth_login_codes (
    id          UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    code_hash   VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,

    CONSTRAINT pk_oauth_login_codes PRIMARY KEY (id),
    CONSTRAINT fk_oauth_login_codes_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_oauth_login_codes_code_hash ON oauth_login_codes (code_hash);
