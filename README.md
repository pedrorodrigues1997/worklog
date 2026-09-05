# io — backend

Backend for a cloud-first time-tracking SaaS. Kotlin + Spring Boot, PostgreSQL, modular
monolith organised by business domain.

The frontend lives in a separate repository. This service exposes a JSON REST API only.

## Prerequisites

- JDK 21
- Docker (for local PostgreSQL and for the Testcontainers-backed tests)

Maven itself is not required — use the bundled wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Configure your environment

```bash
cp .env.example .env
```

Then fill in `JWT_SECRET` (`openssl rand -base64 48`). The database values already match
`docker-compose.yml`, so nothing else needs changing for local work.

`.env` is git-ignored and holds real values; `.env.example` is the committed template and
must never contain a secret.

Docker Compose reads `.env` automatically. **Spring Boot does not** — export it into your
shell before starting the backend:

```bash
set -a; source .env; set +a
```

Every variable can also be set directly in the environment instead; `.env` is only a
convenience for local development. Deployed environments should set them on the platform
and never ship a `.env` file.

## Start PostgreSQL

```bash
docker compose up -d
```

That starts PostgreSQL 17 on `localhost:5432` using the `POSTGRES_*` values from `.env`,
defaulting to database `io`, user `io`, password `io`.

```bash
docker compose down     # stop
docker compose down -v  # stop and delete the data volume
```

## Run the backend

```bash
set -a; source .env; set +a
./mvnw spring-boot:run
```

`JWT_SECRET` has no default — the application refuses to start without it rather than
signing tokens with a key that is in the repository.

Or build and run the jar:

```bash
./mvnw package
java -jar target/io-0.0.1-SNAPSHOT.jar
```

The application listens on `http://localhost:8080`. On startup Flyway applies any pending
migrations, then Hibernate validates that the entity mappings match the resulting schema —
startup fails loudly if a migration and a mapping have drifted apart.

Health check: `GET http://localhost:8080/actuator/health`

## Run the tests

```bash
./mvnw test
```

Docker must be running: the integration tests start a real PostgreSQL container via
Testcontainers rather than substituting an in-memory database.

## Environment variables

| Variable            | Default                                  | Notes                                     |
| ------------------- | ---------------------------------------- | ----------------------------------------- |
| `DATABASE_URL`      | `jdbc:postgresql://localhost:5432/io`    | JDBC URL. Must start with `jdbc:`.        |
| `DATABASE_USERNAME` | `io`                                     |                                           |
| `DATABASE_PASSWORD` | `io`                                     | Local default only. Never used in a deployed environment. |
| `JWT_SECRET`        | *(none — required)*                      | HMAC signing key, ≥ 32 bytes. Startup fails if missing or too short. |
| `JWT_ISSUER`        | *(none — required)*                      | `iss` claim, and the value every token is checked against. Must be an absolute URI — this API's own base URL. Startup fails otherwise. |
| `JWT_ACCESS_TOKEN_TTL`  | `15m`                                | Access-token lifetime. |
| `JWT_REFRESH_TOKEN_TTL` | `30d`                                | Refresh-token lifetime. |
| `JWT_CLOCK_SKEW`        | `5s`                                 | Tolerance on `exp`/`nbf`. |
| `REFRESH_TOKEN_CLEANUP_CRON` | `0 0 3 * * *`                   | When expired refresh tokens are deleted. |
| `RATE_LIMIT_ENABLED`    | `true`                               | Throttling of `/api/auth`. |
| `RATE_LIMIT_CAPACITY`   | `20`                                 | Requests per window per client. |
| `RATE_LIMIT_WINDOW`     | `1m`                                 | The window. |
| `RATE_LIMIT_CLIENT_IP_HEADER` | *(empty)*                      | Header carrying the real client IP. Set to `CF-Connecting-IP` behind Cloudflare; leave empty otherwise. |

The database defaults exist so a developer can clone and run. In any deployed environment
all of them must be set explicitly. Locally they come from `.env` (see above).

Rotating `JWT_SECRET` invalidates every outstanding access token immediately; refresh
tokens are unaffected, since they are database rows rather than signed values.

> **Railway note:** Railway injects a `DATABASE_URL` in `postgresql://user:pass@host/db`
> form, which is *not* a valid JDBC URL. Set `DATABASE_URL` to the `jdbc:postgresql://…`
> form and supply the username and password separately.

## Registration endpoint

`POST /api/auth/register` — public, no authentication required. Every other endpoint
requires authentication by default.

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
        "firstName": "John",
        "lastName": "Smith",
        "email": "john@example.com",
        "password": "correct-horse-battery"
      }'
```

**201 Created**

```json
{
  "id": "dee3be23-7dee-4dd8-a11b-2fa816140343",
  "firstName": "John",
  "lastName": "Smith",
  "email": "john@example.com",
  "createdAt": "2026-09-05T06:07:46.407625200Z"
}
```

The password and its hash are never returned by any endpoint.

Rules: first and last name required (≤ 100 chars), valid email (≤ 320 chars, stored
lowercased), password 12–128 characters.

### Errors

Errors are RFC 9457 `application/problem+json`.

**400 Bad Request** — validation failure, with a message per field:

```json
{
  "status": 400,
  "title": "Validation failed",
  "detail": "Request validation failed",
  "instance": "/api/auth/register",
  "errors": {
    "email": "Email must be a valid email address",
    "password": "Password must be between 12 and 128 characters"
  }
}
```

**409 Conflict** — the email is already registered:

```json
{
  "status": 409,
  "title": "Email already registered",
  "detail": "An account with this email address already exists",
  "instance": "/api/auth/register"
}
```

**500 Internal Server Error** — generic message only; the cause is logged server-side.

## Authentication

Stateless bearer-token authentication. There is no HTTP session — the access token is the
whole session.

| Endpoint | Auth | Purpose |
| --- | --- | --- |
| `POST /api/auth/register` | public | create an account |
| `POST /api/auth/login` | public | exchange credentials for tokens |
| `POST /api/auth/refresh` | public | exchange a refresh token for a new pair |
| `POST /api/auth/logout` | refresh token | revoke a refresh token |
| everything else | access token | default-deny |

Neither `refresh` nor `logout` requires an access token, for the same reason: both exist to
be used once the access token has expired, so requiring one would make them useless exactly
when they are needed. The refresh token is the credential in both cases.

Those endpoints also run on a separate security filter chain with no bearer-token support,
so a stale `Authorization` header is *ignored* rather than rejected. Clients routinely
attach their access token to every request; without this, logging out with an expired token
would fail authentication before reaching the handler.

### Access tokens

Short-lived (15 minutes by default) HS256 JWTs. Claims are `iss`, `sub` (the user id),
`iat`, `exp` and `jti` — nothing else. A JWT is signed but **not** encrypted, so anyone
holding one can read every claim; names, email addresses and future role assignments stay
out of it. The user id is all the server needs to look up the rest.

Send it as `Authorization: Bearer <accessToken>`. A missing, expired, malformed, wrongly
signed, or wrongly issued token yields `401`.

Clock skew tolerance is 5 seconds, not Spring's 60-second default: we are our own issuer,
so there is no third-party clock to drift from, and a minute of grace on a 15-minute token
is a meaningful extension of its life.

Access tokens cannot be revoked inside their lifetime — there is no denylist, deliberately,
since that means shared state we do not want yet. The window is closed at the point of use
instead: see *Acting on behalf of a user* below.

### Refresh tokens

Opaque 256-bit random values, **not** JWTs, with their state held in the `refresh_tokens`
table so they can actually be revoked. Only a SHA-256 of each token is stored, so a database
dump yields no usable tokens. SHA-256 rather than Argon2 is deliberate: these are CSPRNG
values with nothing to brute-force, and a slow hash would only make every refresh expensive.

Every refresh **rotates**: the presented token is revoked and a new one issued, so a leaked
token is useful only until the real client next refreshes.

Presenting a token that was already *rotated away* means two parties hold the same token —
the copy was stolen. Since the thief cannot be distinguished from the real client, every
session for that user is revoked. A token revoked by *logout* carries no such signal
(it has no replacement recorded), so replaying one is simply rejected and other devices
stay signed in.

### Logout

Takes the refresh token to revoke and needs nothing else. Holding the token is the whole
authorisation: whoever has it can already mint access tokens with it, so letting them
destroy it instead is strictly safer than refusing. Unknown tokens return `204` unchanged —
logout must not become an oracle for which tokens exist.

Expired refresh tokens are deleted on a schedule (`REFRESH_TOKEN_CLEANUP_CRON`, 03:00 by
default). Revoked-but-unexpired rows are kept: they are what makes reuse detection work.

### Example

```bash
# 1. Log in.
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"john@example.com","password":"correct-horse-battery"}'

# {"accessToken":"eyJ...","refreshToken":"h1Bd...","tokenType":"Bearer","expiresIn":900}

# 2. Call a protected endpoint.
curl http://localhost:8080/api/users/me \
  -H 'Authorization: Bearer <accessToken>'

# 3. Rotate the token pair once the access token nears expiry.
curl -s -X POST http://localhost:8080/api/auth/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refreshToken>"}'

# 4. Log out, revoking the refresh token.
curl -s -X POST http://localhost:8080/api/auth/logout \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <accessToken>' \
  -d '{"refreshToken":"<refreshToken>"}'
```

Login failures return `401` with an identical body whether the address is unknown, the
password is wrong, or the account is deleted. The server also performs a hash comparison
even when no account exists, so response timing does not disclose which addresses are
registered.

### Acting on behalf of a user

Inject `CurrentUser` and call `currentUser.id()` for the caller's id. It reads the `sub`
claim of the signature-verified token from the security context — never a path variable,
body field or header — which is what stops a request from acting as someone else by editing
its own payload. Endpoints take no client-supplied user id at all.

For anything that acts on the account rather than merely identifying it, inject `ActiveUser`
and call `activeUser.require()`. That resolves the token to a live, non-deleted user and
throws otherwise. Since access tokens are not revocable within their lifetime, this lookup
is what stops a token outliving the account behind it — so sensitive operations should go
through it rather than trusting `sub` alone.

### Rate limiting

`POST /api/auth/**` is throttled per client: 20 requests a minute by default, answered with
`429` and a `Retry-After` header. Login and register both run Argon2id — roughly 40ms of CPU
and 16MB of memory each, by design — which makes them a denial-of-service lever for a caller
with no credentials at all. The answer is to cap the rate, never to weaken the hash.

This is the inner of two layers and is deliberately modest: buckets live in memory, so the
effective limit multiplies by the instance count and resets on deploy. Edge protection
(Cloudflare) is what should absorb real volume. Behind a proxy, set
`RATE_LIMIT_CLIENT_IP_HEADER` to a header the edge **overwrites** (`CF-Connecting-IP`) —
never `X-Forwarded-For`, which a caller can prepend to and earn a fresh bucket per request.

## Layout

Code is organised by business domain, not by technical layer:

```
com.tictac.io
├── common/          API error handling
│   ├── security/    CurrentUser
│   └── ratelimit/   throttling for the auth endpoints
├── user/            the user entity, ActiveUser, GET /api/users/me
└── authentication/  registration, login, refresh, logout, HTTP security
    └── token/       JWT issuing/decoding, refresh-token state and cleanup
```

Database migrations live in `src/main/resources/db/migration` and are the single source of
truth for the schema.

## Passwords

Hashed with Argon2id through Spring Security's `Argon2PasswordEncoder`, wrapped in a
`DelegatingPasswordEncoder` so every stored hash carries an algorithm prefix
(`{argon2}$argon2id$…`). That prefix is what allows the parameters — or the algorithm — to
change later without invalidating existing passwords.
