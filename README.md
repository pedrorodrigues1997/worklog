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
| `INVITATION_TTL`        | `7d`                                 | How long an organization invitation stays usable. |
| `RATE_LIMIT_ENABLED`    | `true`                               | Throttling of `/api/auth`. |
| `RATE_LIMIT_CAPACITY`   | `20`                                 | Requests per window per client. |
| `RATE_LIMIT_WINDOW`     | `1m`                                 | The window. |
| `RATE_LIMIT_CLIENT_IP_HEADER` | *(empty)*                      | Header carrying the real client IP. Set to `CF-Connecting-IP` behind Cloudflare; leave empty otherwise. |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | *(empty)*        | Enables Google sign-in. Blank = not registered, and the OAuth endpoints do not exist. |
| `OAUTH2_SUCCESS_REDIRECT_URI` | `http://localhost:3000/auth/callback` | Frontend landing page after sign-in. |
| `OAUTH2_FAILURE_REDIRECT_URI` | `http://localhost:3000/auth/error`    | Frontend landing page on failure. |
| `OAUTH2_LOGIN_CODE_TTL` | `60s`                                | Lifetime of the single-use handoff code. |

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
| `GET /oauth2/authorization/google` | public | start a Google sign-in |
| `POST /api/auth/oauth/exchange` | one-time code | finish it, and receive the same token pair |
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

### Sign in with Google

Optional. With no client id configured, Google is not registered, the OAuth filter chain is
never created, and the endpoints below do not exist — so the application runs locally with
none of this set.

```
GET  /oauth2/authorization/google    browser starts here
GET  /login/oauth2/code/google       Google redirects back here
POST /api/auth/oauth/exchange        frontend trades the code for tokens
```

#### How the flow actually works

1. **Start.** The browser goes to `GET /oauth2/authorization/google`. Spring Security builds
   an authorization request, stores it in a short-lived HTTP session, and redirects to
   Google with our `client_id`, the scopes (`openid profile email`), a callback URL and a
   random `state`. Nothing of ours is sent — no secret, no user data.

2. **Google authenticates the person.** They sign in and consent, on Google's domain. We
   never see their Google password.

3. **Callback.** Google redirects the browser to `GET /login/oauth2/code/google?code=…&state=…`.
   `state` is compared against the stored value; a mismatch aborts the flow. That is the CSRF
   defence for this step, which is why CSRF filtering is disabled on this chain — `state` is
   the mechanism OAuth defines for it.

4. **Code exchange, server to server.** Spring Security calls Google's token endpoint
   directly from the backend with the code *and our client secret*. The secret never touches
   the browser. Google returns an access token and an **ID token** — a JWT signed by Google.

5. **ID token validation.** Spring fetches Google's public keys and checks the signature,
   that `aud` is our client id (so a token minted for a different application is refused),
   that `iss` is Google, and that it has not expired. This is the step that makes the claims
   trustworthy, and it is entirely Spring Security's code — none of it is ours.

6. **Our half begins.** `OAuthLoginSuccessHandler` receives the verified claims.
   `GoogleIdentityExtractor` maps them to an `OAuthUserIdentity` (subject, email,
   `email_verified`, names). `OAuthAuthenticationService` turns that into an internal user id
   by the rules under *Account linking*. Google's own access token is discarded, and the
   session that carried the flow is destroyed.

7. **Handoff.** The backend mints a single-use code, stores its SHA-256 with a 60-second
   expiry, and redirects the browser to the frontend with `?code=…`.

8. **Exchange.** The frontend POSTs that code to `/api/auth/oauth/exchange` and receives
   exactly the token pair a password login returns.

In short:

```
browser → Google → callback → (backend ⇄ Google: code + secret → ID token)
        → verify signature/audience/expiry → internal users.id
        → one-time code → frontend → our access + refresh tokens
```

`users.id` stays the only identity the application knows. Google's `sub` is never a primary
key — it is a lookup key in `user_identities`, and every downstream feature sees the same
UUID whether the person signed in with a password or with Google.

#### Why a one-time code rather than tokens in the URL

Google redirects to the *backend*, but the tokens have to reach a separate SPA. Putting them
in the redirect URL would write a 30-day refresh token into browser history and into any log
that records the landing URL. The code is single-use and expires in 60 seconds — the same
shape as the authorization code we just consumed from Google, and for the same reason.

```bash
# Browser: GET http://localhost:8080/oauth2/authorization/google
# ...consent...
# Browser lands on: http://localhost:3000/auth/callback?code=Yl3n...

curl -s -X POST http://localhost:8080/api/auth/oauth/exchange \
  -H 'Content-Type: application/json' \
  -d '{"code":"Yl3n..."}'
# → exactly the same body a password login returns
```

Everything after that point is identical for both sign-in methods: one token model, one
refresh mechanism, one logout. Google's access token is never sent to the frontend.

On failure the browser goes to `OAUTH2_FAILURE_REDIRECT_URI` with `?error=oauth_failed`,
`oauth_invalid_identity` or `oauth_linking_not_allowed`. Google's error detail is logged,
never reflected into the URL.

### Account linking

One account can carry a password and Google at once. `user_identities` holds
`(user_id, provider, provider_user_id)`, unique on `(provider, provider_user_id)` — which is
what stops two of our users claiming the same Google account — and unique on
`(user_id, provider)`, so an account cannot accumulate several logins from one provider that
nobody can tell apart. A separate table rather than columns on `users` because the
relationship is one-to-many; provider columns would mean a schema change per provider added.

Sign-in resolves in this order:

1. **Known identity** → that user. Matching is on Google's `sub`, never the email, so
   changing your Google address keeps your account, and the address we hold is not silently
   rewritten by signing in.
2. **New identity, verified email, address already registered** → link to that account. The
   password still works; the account gains a second way in rather than a replaced one.
3. **New identity, verified email, address unknown** → create a user with no password hash.
   Password login already refuses a null hash, so the account cannot be entered that way
   until its owner sets one.
4. **New identity, unverified email** → refused.

Step 4 is the load-bearing one. Attaching an unverified address to an existing account would
let anyone who can type a victim's address into a signup form inherit their account; creating
a *new* account on one lets them squat an address they do not own. Note the gate applies only
to establishing the link — once it exists, sign-in goes through step 1 and never consults the
email again.

Google always returns `email_verified`, so the rule is simply to believe it; a missing claim
counts as unverified. A second provider would need its own extractor, because that signal is
not the same everywhere — Microsoft, for instance, does not issue the claim at all.

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

## Organizations

The customer is the organization, not the individual. A user reaches organization data only
through a membership, and a user may belong to several organizations with a different role
in each — so nothing about the current tenant is ever read off the user row.

```
users  ──<  organization_members  >──  organizations
                     role
```

Every endpoint below requires a valid access token. There is no anonymous access and no
endpoint that lists organizations globally.

| Method   | Path                                            | Who may call it        |
| -------- | ----------------------------------------------- | ---------------------- |
| `POST`   | `/api/organizations`                            | any authenticated user |
| `GET`    | `/api/organizations`                            | any authenticated user |
| `GET`    | `/api/organizations/{id}`                       | any member             |
| `PATCH`  | `/api/organizations/{id}`                       | `OWNER`, `ADMIN`       |
| `DELETE` | `/api/organizations/{id}`                       | `OWNER`                |
| `GET`    | `/api/organizations/{id}/members`               | any member             |
| `PATCH`  | `/api/organizations/{id}/members/{userId}`      | `OWNER`, `ADMIN`       |
| `DELETE` | `/api/organizations/{id}/members/{userId}`      | `OWNER`, `ADMIN`       |
| `POST`   | `/api/organizations/{id}/transfer-ownership`    | `OWNER`                |
| `POST`   | `/api/organizations/{id}/invitations`           | `OWNER`, `ADMIN`       |

### Example

```bash
TOKEN=...   # accessToken from POST /api/auth/login

# Create an organization. The caller becomes its OWNER; there is no way to create one
# owned by somebody else, and no userId field is read from the body.
curl -X POST http://localhost:8080/api/organizations \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Acme Consulting"}'
# {"id":"9288...","name":"Acme Consulting","role":"OWNER","createdAt":"2026-09-06T07:46:42Z"}

# The organizations you belong to, with your role in each.
curl http://localhost:8080/api/organizations -H "Authorization: Bearer $TOKEN"
# [{"id":"9288...","name":"Acme Consulting","role":"OWNER","createdAt":"..."}]

# Members. Safe fields only — no password hash, ever.
curl http://localhost:8080/api/organizations/$ORG_ID/members -H "Authorization: Bearer $TOKEN"
# [{"userId":"f32f...","firstName":"Pedro","lastName":"Rodrigues",
#   "email":"pedro@example.com","role":"OWNER","joinedAt":"..."}]

# Promote a member.
curl -X PATCH http://localhost:8080/api/organizations/$ORG_ID/members/$USER_ID \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"role":"ADMIN"}'
```

### Roles

`OWNER` · `ADMIN` · `MEMBER`, ranked in that order. Two rules cover the whole membership
policy, and both live on `OrganizationRole` rather than being spelled out per endpoint:

- **You may only act on someone you outrank.** An `ADMIN` may manage `MEMBER`s but not a
  fellow `ADMIN`; nobody outranks the `OWNER`, and no role outranks itself. That single rule
  is what keeps an organization from being left ownerless — the owner can be neither removed
  nor demoted, by an admin or by themselves.
- **You may only hand out a role at or below your own, and never `OWNER`.** An `ADMIN` can
  promote a `MEMBER` to `ADMIN`; nobody can create a second owner. Transferring ownership
  has to demote the current owner in the same step, so it belongs in its own operation and
  is not implemented yet.

There is exactly one `OWNER` per organization, enforced by a partial unique index rather
than by application code alone.

### Tenant isolation

Authentication answers *who is this?*; authorization answers *may they operate on this
organization?* They are separate checks, and the second one is not optional.

The organization id comes from the URL — the frontend has to be able to choose which tenant
it is working in — and is therefore untrusted. What makes it safe is that it is only ever
used as one half of a membership lookup whose other half comes from the access token:

```
access token → user id ─┐
                        ├─→ organization_members → role → allowed?
organization id (URL) ──┘
```

Every organization-scoped service method opens with `OrganizationAccess.require(...)`,
optionally naming the roles that may proceed. Nothing else queries by organization id:

```kotlin
// any member may read
val context = organizationAccess.require(organizationId)

// administrative operations name the roles that may proceed
val context = organizationAccess.require(organizationId, OrganizationRole.OWNER, OrganizationRole.ADMIN)
```

Add new organization-scoped endpoints the same way. A route that queries by organization id
without going through that gate is a cross-tenant data leak, which is why the check lives in
one place instead of being copied into every controller.

**Failures are deliberately shaped.** A caller who is *not* a member gets `404`, identical
whether the organization is real, soft-deleted, or imaginary — organization ids travel in
URLs and support tickets, and a `403`/`404` split would turn any leaked id into an oracle
for whether that tenant exists. A caller who *is* a member but holds the wrong role gets
`403`: they can already see the organization in their own listing, so saying so costs
nothing.

### Ownership transfer

Ownership moves through one named operation and nowhere else. The membership API refuses to
assign or revoke `OWNER` precisely so that this is the only route to it — a transfer touches
two members at once, and expressing it as two role edits is how an organization ends up with
two owners or none.

```bash
# OWNER only. The target must already be a member of this organization.
curl -X POST http://localhost:8080/api/organizations/$ORG_ID/transfer-ownership \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"userId":"22ae501a-3589-44cc-805e-64cefa4e9d03"}'
```

```json
{
  "organizationId": "7def7001-...",
  "previousOwner": { "userId": "48106a97-...", "email": "pedro@example.com", "role": "ADMIN" },
  "newOwner":      { "userId": "22ae501a-...", "email": "john@example.com",  "role": "OWNER" }
}
```

The outgoing owner becomes `ADMIN`, not `MEMBER` — a handover should not also strip the
person who built the organization of the ability to run it.

| Situation | Response |
| --- | --- |
| caller is `ADMIN` or `MEMBER` | `403` |
| caller is not a member | `404` |
| target is not a member of this organization | `404` |
| target user does not exist | `404` |
| target's account is closed | `409` |
| target is the caller | `400` |
| ownership moved while the request was in flight | `409` |

Identification is by user id, never by email: an email would let a caller test which
addresses have accounts, and bringing a new person in is an invitation, not a transfer.

**Concurrency.** Two transfers of the same organization serialise on a `SELECT … FOR UPDATE`
of its `OWNER` row. The loser blocks; when it resumes, the row it was waiting on no longer
matches `role = 'OWNER'`, so it finds no owner to demote and fails with `409` rather than
racing to a second owner. Inside the transaction the demote is written *before* the promote,
because the partial unique index permits one `OWNER` row per organization at any instant —
promoting first would violate it on every transfer. The intermediate zero-owner state exists
only inside that transaction and no other session can observe it.

### Deletion

`DELETE` is a soft delete: it stamps `deleted_at` and nothing else. Memberships survive, and
so will the clients, projects and time entries that eventually hang off an organization —
destroying a customer's data on one API call is not recoverable. A soft-deleted organization
drops out of every listing and is refused by `OrganizationAccess`, so it is unreachable to
everyone including its owner. Purging and restoring are separate deliberate operations.

### Joining an organization

Only two things create a membership: founding an organization, and accepting an invitation
(see [Invitations](#invitations)). There is deliberately no "add this email to my
organization" endpoint — that would attach a stranger's account to a tenant without their
consent. An invitation is the consenting version of the same thing, and it grants `MEMBER`
and nothing else.

## Closing an account

```
DELETE /api/users/me      → 204
```

Soft delete, consistent with the rest of the model: `users.deleted_at` is stamped and the row
stays. Login, the OAuth paths, `ActiveUser.require()` and the organization member listing all
already treat that as gone. Refresh tokens are revoked in the same transaction — `rotate()`
looks a token up and never consults the user row, so a live refresh token would otherwise
keep minting access tokens for a closed account indefinitely.

**You cannot close an account that still owns an organization.**

```json
{
  "status": 409,
  "title": "Account still owns organizations",
  "detail": "Transfer ownership of your organizations before closing your account",
  "instance": "/api/users/me",
  "organizations": [{ "id": "7def7001-...", "name": "Acme Consulting" }]
}
```

The lifecycle is therefore: **transfer ownership → then close.** Every alternative was worse.
Promoting some other member is a decision the API has no basis to make; deleting the
organization destroys a business's data as a side effect of one person leaving; and dropping
the `OWNER` membership silently leaves an active organization nobody can administer and
nobody can be granted a role in — because granting a role requires already being a member.

Owning a *soft-deleted* organization does not block closure. That organization is already
unreachable, and requiring a transfer would be a dead end since transfer refuses deleted
organizations too.

**Non-owner memberships survive closure.** A closed `MEMBER` or `ADMIN` keeps their
`organization_members` row. The row is already inert — the account cannot authenticate, and
closed accounts are filtered out of every member listing — so deleting it would destroy the
record of who was in an organization to no observable benefit, and soft-deleting it would
mean inventing a whole membership lifecycle for a state nothing can see. The one cost is that
an organization's member count and its `organization_members` row count can differ; that
matters when seats are billed, where the fix is to count through `users.deleted_at`.

The invariant all of this protects: **an active organization is never owned by a closed
account.**

## Invitations

How a company gets its people in. Until this existed, the only way into an organization was
founding it — everything else required writing `organization_members` rows by hand.

```
OWNER / ADMIN ──▶ invite email ──▶ invitation + random token ──▶ (email, later)
                                                                      │
                          ┌───────────────────────────────────────────┘
                          ▼
          has an account ──▶ sign in ──▶ POST /api/invitations/accept    ──▶ MEMBER
          no account     ──▶            POST /api/invitations/register   ──▶ MEMBER
```

Email delivery is not implemented. Until it is, the raw token comes back in the create
response so the flow can be exercised end to end.

| Method | Path | Who |
| ------ | ---- | --- |
| `POST` | `/api/organizations/{orgId}/invitations` | `OWNER`, `ADMIN` |
| `POST` | `/api/invitations/accept` | any authenticated user |
| `POST` | `/api/invitations/register` | public |

```bash
# Invite. The response carries the raw token - the only moment it exists outside the database.
curl -X POST http://localhost:8080/api/organizations/$ORG_ID/invitations \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"bob@example.com"}'

# Already has an account: sign in first, then accept. Identity comes from the access token.
curl -X POST http://localhost:8080/api/invitations/accept \
  -H "Authorization: Bearer $BOBS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"token":"..."}'

# No account yet: register and join in one transaction.
curl -X POST http://localhost:8080/api/invitations/register -H 'Content-Type: application/json' \
  -d '{"token":"...","firstName":"Bob","lastName":"Smith",
       "email":"bob@example.com","password":"correct-horse-battery"}'
```

### Why acceptance is two endpoints

The brief for this feature sketched one public `POST /api/invitations/accept`. The security
configuration rules that out, and the split is better anyway: the `@Order(1)` chain that
serves the public POSTs **deliberately ignores the `Authorization` header** (see
[Authentication](#authentication)), so an endpoint on it cannot know who is calling —
and acceptance by an existing user has to know exactly that, from a verified token rather
than from the request body.

So `/accept` sits on the default-deny chain and requires authentication, while `/register`
is public and takes the credentials registration has always required. Possessing an
invitation is not the same as having proved who you are.

### Lifecycle

Three timestamps, no status column — pending, expired and accepted are all derivable, and a
fourth representation is a fourth thing that can disagree with the others:

```
pending  = accepted_at IS NULL AND expires_at >  now()
expired  = accepted_at IS NULL AND expires_at <= now()
accepted = accepted_at IS NOT NULL
```

Invitations last **7 days** by default (`INVITATION_TTL`) — long enough to survive a weekend,
short enough that a forwarded link stops working before it matters. An accepted invitation
can never be accepted again.

At most one *un-accepted* invitation exists per organization and address, enforced by a
partial unique index. "Pending" can't go in an index predicate — `now()` isn't immutable —
so the index covers the wider un-accepted set and the application deletes a lapsed invitation
before issuing its replacement. Re-inviting after an invitation expires works; issuing a
second live one does not (`409`).

### The token

A 256-bit CSPRNG value, URL-safe Base64, stored **only as its SHA-256** — the same recipe as
refresh tokens and OAuth handoff codes, now shared as `SecureToken`. It is not the row id,
not a JWT, and appears in exactly one place: the create response. Nothing persists or logs
the raw value, and the entity's `toString` omits the hash as well.

Acceptance hashes the presented token and looks the invitation up by hash. Single-use:
`accepted_at` is stamped in the same transaction as the membership.

### What acceptance guarantees

- **Always `MEMBER`.** There is no role field on an invitation. Letting the inviter choose
  would let an ADMIN mint another ADMIN — or an OWNER — through a side door that bypasses the
  rank rules the membership API enforces.
- **The invitee is the invited person.** For an existing user the email is compared against
  the account behind a signature-verified access token, never against anything in the body —
  Bob holding Alice's link gets `403`. For a new user the account is created *at* the invited
  address, so an invitation to Alice cannot create Bob.
- **One transaction.** Membership and `accepted_at` land together; a failure anywhere leaves
  no account, no membership and an invitation that still works.
- **One organization.** Neither acceptance endpoint takes an organization id — it comes off
  the invitation row — so there is no parameter to manipulate and no way to point an
  invitation for one company at membership in another.
- **Once.** Concurrent acceptance serialises on `SELECT … FOR UPDATE` of the invitation row,
  and `organization_members (organization_id, user_id)` remains the final guarantee.

| Situation | Response |
| --- | --- |
| MEMBER tries to invite | `403` |
| non-member tries to invite | `404` |
| invalid email | `400` (the same validation registration applies) |
| already a member · duplicate pending invitation | `409` |
| unknown token | `404` |
| expired · already accepted · organization since closed | `409` |
| authenticated user is not the invitee | `403` |
| registration email ≠ invited email | `422` |
| registration email already has an account | `409` |

## Projects

A project belongs to exactly one organization, and organization membership grants nothing
inside it. Those are two different questions, and keeping them apart is what lets a company
have work that not everybody is on:

```
organization membership  →  does this person belong to the company?
project membership       →  is this person assigned to this piece of work?
```

```
Acme
├── Pedro   OWNER      ├── Website Redesign  →  Pedro, Alice
├── Alice   ADMIN      └── Mobile App        →  Pedro, Bob, Sarah
├── Bob     MEMBER
└── Sarah   MEMBER          Alice ∈ Acme, but Alice ∉ Mobile App
```

Every endpoint is nested under its organization, so the tenant is in the path and is checked
on every call. All require a valid access token.

| Method   | Path | Who may call it |
| -------- | ---- | --------------- |
| `POST`   | `/api/organizations/{orgId}/projects` | `OWNER`, `ADMIN` |
| `GET`    | `/api/organizations/{orgId}/projects` | any member (scoped, below) |
| `GET`    | `/api/organizations/{orgId}/projects/{projectId}` | admins, or assigned member |
| `PATCH`  | `/api/organizations/{orgId}/projects/{projectId}` | `OWNER`, `ADMIN` |
| `GET`    | `/api/organizations/{orgId}/projects/{projectId}/members` | admins, or assigned member |
| `POST`   | `/api/organizations/{orgId}/projects/{projectId}/members` | `OWNER`, `ADMIN` |
| `DELETE` | `/api/organizations/{orgId}/projects/{projectId}/members/{userId}` | `OWNER`, `ADMIN` |

### Example

```bash
# Create. The organization comes from the URL - there is no body field that could
# point the project at a different one.
curl -X POST http://localhost:8080/api/organizations/$ORG_ID/projects \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Website Redesign","description":"Redesign the company website"}'
# {"id":"...","name":"Website Redesign","description":"...","isActive":true,
#  "createdAt":"...","updatedAt":"..."}

# Assign an organization member to it.
curl -X POST http://localhost:8080/api/organizations/$ORG_ID/projects/$PROJECT_ID/members \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"userId":"..."}'

# Archive it. There is no DELETE - see below.
curl -X PATCH http://localhost:8080/api/organizations/$ORG_ID/projects/$PROJECT_ID \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"isActive":false}'
```

### Listing is scoped to the caller

`GET .../projects` returns two different lists behind one URL, deliberately:

- **OWNER / ADMIN** — every project in the organization.
- **MEMBER** — only the projects they are assigned to. A member with no assignments gets an
  empty list, even though the organization has projects.

That is what makes this endpoint usable directly as the frontend's project selector. The
optional `active` query parameter narrows it: `active=true` gives the projects that are
selectable for new work, which is the query time tracking will be built on, `active=false`
gives the archive, and omitting it gives both.

### Authorization

The full chain, evaluated in this order on every project-scoped call:

```
access token → organization membership → organization role → project in that organization
             → project assignment → permission
```

The first two links are `OrganizationAccess`, unchanged and not reimplemented. `ProjectAccess`
adds the last two, and every project service method starts there:

```kotlin
// anyone who can see the project - an organization admin, or an assigned member
val context = projectAccess.require(organizationId, projectId)

// administrative operations name the roles that may proceed
val context = projectAccess.require(organizationId, projectId, OrganizationRole.OWNER, OrganizationRole.ADMIN)
```

**Both ids are untrusted, and neither is ever used alone.** A project is looked up by
`(project id, organization id)` together, so a project id borrowed from another tenant
resolves to nothing however valid it is. Resolving by id and comparing the organization
afterwards would work too — but only until someone forgets the second step, and here there is
no second step to forget.

**Failures are shaped like the organization ones.** An organization MEMBER who is not assigned
gets `404`, identical to a project that does not exist: they have no legitimate way to learn
that a project exists, so a `403` would leak it. An assigned member who lacks the *role* gets
`403` — they can already see the project, so saying so costs nothing. Visibility is checked
before the role, so the second case can never expose the first.

### Archiving, not deleting

There is no `DELETE` for a project. Time entries will point at projects, and an archived
project still has to render every historical entry that references it, so `is_active` is the
whole lifecycle: `PATCH {"isActive": false}` archives, `true` restores. Archiving is
idempotent, keeps the project readable, and keeps its assignments.

Project names are deliberately not unique within an organization — archiving "Website
Redesign" and starting a new one next year under the same name is normal, and the two rows
must be able to coexist.

### Membership rules

Assignment is always an explicit act by an `OWNER` or `ADMIN`. Nobody is added to a project
just for being in the organization. Rejected: a user who is not an organization member, one
from another organization, one that does not exist (all `404`, so the endpoint cannot be used
to probe for account ids), a closed account and a duplicate assignment (both `409`).

**Self-removal is not offered.** It was considered and declined: nothing in this codebase lets
anyone leave anything on their own — an organization member cannot remove themselves from an
organization, and neither can the owner — so making project assignment the sole exception
would be inconsistent for no product need, and it would let someone quietly drop off work an
administrator had assigned them. "Leave" is worth building once, properly, at both levels. An
administrator removing *their own* assignment is allowed and is not a special case.

### Leaving an organization

Removing someone from an organization deletes their project assignments **in that
organization**, in the same transaction:

```
Remove Alice from Acme → remove Alice from every Acme project → remove Alice from Acme
```

A project member who is no longer an organization member is a state the domain does not
allow. It cannot be a foreign key — `project_members` reaches the organization only through
`projects.organization_id`, one hop away — so it is enforced in the service, atomically.
Assignments in a *different* organization Alice still belongs to are untouched.

**Closing an account is deliberately different.** Removal breaks the invariant and so must
cascade; closure does not — the person is still an organization member, their account is
simply closed — so their assignment rows stay, inert, and are filtered out of every listing
along with the closed accounts themselves.

## Project categories

A category is a **kind of work within one project** — a subdivision, not an entity of its
own. There is no global category list: "Development" in one project and "Development" in
another are two unrelated rows, because a category only means something relative to the
project that defines it.

```
Website Redesign          Marketing Campaign
├── Development           ├── Content
├── Design                ├── Social Media
├── Meetings              ├── Advertising
└── Testing               └── Meetings
```

A category is **optional** on a time entry, and a project with zero categories is valid.
Nothing is created by default and nobody has to invent a taxonomy to log an hour.

| Method  | Path | Who |
| ------- | ---- | --- |
| `POST`  | `/api/organizations/{orgId}/projects/{projectId}/categories` | `OWNER`, `ADMIN` |
| `GET`   | `/api/organizations/{orgId}/projects/{projectId}/categories` | anyone who can see the project |
| `GET`   | `/api/organizations/{orgId}/projects/{projectId}/categories/{categoryId}` | anyone who can see the project |
| `PATCH` | `/api/organizations/{orgId}/projects/{projectId}/categories/{categoryId}` | `OWNER`, `ADMIN` |

```bash
curl -X POST http://localhost:8080/api/organizations/$ORG_ID/projects/$PROJECT_ID/categories \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Development","description":"Software development work"}'
```

Categories are project **configuration**, which is what decides who may touch them: `OWNER`
and `ADMIN` configure, a `MEMBER` reads the list and picks from it when tracking time. That
is the same split as project settings and reuses the same gate — there is no second
authorization system.

### Names

Unique per project, **case-insensitively**: `Development` and `development` are the same
category to a person choosing from a dropdown, so they are the same category here. Enforced
by a functional unique index on `(project_id, lower(name))`, not by an application check
alone. The same name in a *different* project is fine.

An archived category still holds its name — reactivating the old "Meetings" is the way back,
not creating a second one nobody can tell apart in a report.

### Lifecycle

```
active  ──▶  inactive  ──▶  active
```

`is_active` is the whole lifecycle and there is **no `DELETE` endpoint**. Time entries point
at categories, so a category is retired and kept; the historical entries naming it must keep
resolving. An inactive category:

- stays visible to `OWNER`/`ADMIN` via `?includeInactive=true`;
- disappears from the default listing everyone else sees;
- **cannot** be chosen for a new timer or a new manual entry (`409`);
- **remains** on every historical entry that already names it, and may still be chosen when
  *editing* one.

A `MEMBER` cannot use `?includeInactive=true` to see more than they should — the flag is
ignored for them rather than refused, since the honest answer to a question about
configuration they do not administer is the active list they were always going to get.

Listing is ordered by name ascending, with an id tiebreaker so the order never depends on
collation.

### The project/category invariant

If a time entry names a category, that category **must belong to the entry's own project**.
This is enforced twice, and neither layer relies on the other being right:

- the application resolves a category by `(category id, project id)` together, so one
  borrowed from a sibling project resolves to nothing;
- the database has a **composite foreign key** from `time_entries (project_category_id,
  project_id)` onto `project_categories (project_category_id, project_id)`. Under the default
  `MATCH SIMPLE` rule it is skipped entirely when the category is null — so "no category"
  stays free while "some other project's category" is impossible to write at all.

Moving an entry to another project therefore drops its category unless a new one is supplied
in the same request.

## Time tracking

The product. A user picks an organization, picks a project they may record against, and
starts a timer — or records the work by hand afterwards.

```
select organization → select project → select category (optional)
                    → title → optional description → start timer → … → stop
```

A time entry is **who + project + optional category + title + optional description + time +
billable**. The title is what the user was working on and is required; everything optional
above is genuinely optional.

All endpoints are nested under the organization, require a valid access token, and are
checked against organization membership, organization role and project access before
anything is written.

| Method   | Path | Purpose |
| -------- | ---- | ------- |
| `POST`   | `/api/organizations/{orgId}/time-entries/timer` | start a timer |
| `GET`    | `/api/organizations/{orgId}/time-entries/timer` | the caller's running timer, or `204` |
| `POST`   | `/api/organizations/{orgId}/time-entries/{id}/stop` | stop it |
| `POST`   | `/api/organizations/{orgId}/time-entries` | a manual entry |
| `GET`    | `/api/organizations/{orgId}/time-entries` | a filtered, paginated list |
| `GET`    | `/api/organizations/{orgId}/time-entries/{id}` | one entry |
| `PATCH`  | `/api/organizations/{orgId}/time-entries/{id}` | correct one |
| `DELETE` | `/api/organizations/{orgId}/time-entries/{id}` | soft delete |

### The timer

```bash
# Start. The endpoint accepts no timestamp - started_at is server time, always.
curl -X POST http://localhost:8080/api/organizations/$ORG_ID/time-entries/timer \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"projectId":"...","projectCategoryId":"...","title":"Implement OAuth",
       "description":"Working on Google OAuth","billable":true}'

# Stop. Also takes no timestamp.
curl -X POST http://localhost:8080/api/organizations/$ORG_ID/time-entries/$ENTRY_ID/stop \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "id": "...", "projectId": "...", "projectName": "Website Redesign",
  "projectCategoryId": "...", "projectCategoryName": "Development",
  "userId": "...", "title": "Implement OAuth", "description": "Working on Google OAuth",
  "startedAt": "2026-09-06T09:00:48Z", "endedAt": null,
  "durationSeconds": 3821, "running": true, "billable": true
}
```

Both category fields are `null` for time tracked straight against a project, which is an
ordinary and fully supported shape:

```json
{ "projectId": "...", "projectCategoryId": null, "projectCategoryName": null,
  "title": "General project work", "description": null }
```

**The database is the source of truth, not the browser.** A running timer is a row whose
`ended_at` is null. Close the tab, switch device, sign in again — `GET .../timer` finds it,
and the elapsed time is computed from the stored `started_at`. Nothing ticks server-side and
no row is rewritten while a timer runs; `durationSeconds` on a running entry is arithmetic
performed for that response. `GET .../timer` returns `204 No Content` when nothing is
running: that is an ordinary state of the world, not a missing resource.

**One running timer per user per organization.** Starting a second is a `409`, not an
instruction to stop the first — a client that silently closed half an hour of someone's work
because a button was double-clicked would be worse than a rejected request. The limit is per
*organization*, so someone consulting for two companies can be on the clock at both.

**A running timer's project and category are fixed.** They record the selection made when it
started; changing them mid-flight would mean the elapsed time belonged partly to one project
and partly to another with nothing recording where the boundary was. To track different work,
stop and start again. Archiving the category (or the project) does not stop a running timer —
it can be stopped normally, it just cannot be chosen for a new one.

**Only the owner may stop their own timer**, administrator or not. Stopping asserts what
someone is doing at this second, and nobody else is in a position to say. An administrator
who needs to close an abandoned timer edits the entry and sets its end explicitly, which
records a considered timestamp rather than "whenever they noticed". Stopping twice is a `409`
and leaves the first stop's timestamp and duration intact.

### Manual entries and editing

```bash
curl -X POST http://localhost:8080/api/organizations/$ORG_ID/time-entries \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"projectId":"...","projectCategoryId":"...","title":"Client meeting",
       "description":"Reviewed homepage designs",
       "startedAt":"2026-09-01T13:00:00+04:00","endedAt":"2026-09-01T15:00:00+04:00",
       "billable":true}'
```

Both ends are required and `endedAt` must be after `startedAt`. **The duration is always
derived by the server** — there is no duration field on any request DTO, so a client-supplied
value has nowhere to land rather than being accepted and ignored. Any edit that moves either
timestamp recalculates it.

**Title and description.** The title is required on every path that records time, trimmed
before it is judged, 1–255 characters; a whitespace-only title is refused. The description is
optional, at most 5000 characters, and blank is stored as nothing. Both fail with **422**, not
400: a missing `projectId` means the request was malformed, while a title of three spaces
means the request was fine and the value is not one the domain accepts — different problems
for a client to handle.

**No entry may span more than 24 hours.** A manual entry or an edit beyond that is refused
(422); a *timer* left running longer stops at the cap instead of being refused, because a
timer nobody can close is worse than one that records a day. The elapsed time shown for a
running timer is capped the same way, so it never displays a number larger than the one it
would record.

`PATCH` accepts `projectId`, `projectCategoryId`, `title`, `description`, `startedAt`,
`endedAt` and `billable`; absent fields are left alone. Because absent and `null` mean the
same thing, removing a category is said explicitly with `{"clearProjectCategory": true}`. Neither the organization nor the user is editable, and neither appears
in the request, so no shape of PATCH moves billable history between tenants or reattributes
someone else's work. `endedAt` cannot be cleared either — "un-stopping" an entry would
resurrect a second running timer.

Overlapping entries are accepted. Someone may legitimately record a client call that ran
through a stretch of development work, and deciding which of two overlapping intervals is
wrong is a reporting question, not something to guess at write time. The one thing that
cannot overlap is two *running* timers.

### Timestamps

Every timestamp on the wire is **ISO-8601 with an offset** and is stored as `timestamptz` —
an absolute point on the timeline. `2026-09-01T13:00:00+04:00` and `2026-09-01T09:00:00Z` are
the same instant and are accepted interchangeably; both come back as the latter. A local time
with no offset (`2026-09-01T09:00:00`) is rejected: "09:00" is not a moment until someone says
where, and guessing on the client's behalf is how a timesheet ends up four hours out.

### Who may track time, and against what

| | Track time | See others' entries | Edit/delete others' entries |
| --- | --- | --- | --- |
| `OWNER` / `ADMIN` | any **active** project in the organization | yes | yes |
| `MEMBER` | active projects they are **assigned** to | no | no |

**Administering a project and tracking time against it are separate questions**, and
administrators pass the second without passing project membership. Requiring an OWNER to
assign themselves to every project just to log their own hours would make project membership
mean two things at once — who is on the work, and who may record against it — and
administrators would pollute the first to get the second.

**Administrators may correct their organization's timesheets.** A member logs eight hours to
the wrong project and leaves for the day; the alternative is that nobody can fix it or
everybody can. Scoped strictly to their own organization.

**A MEMBER's listing is scoped to their own entries.** The scope is applied as a predicate
ANDed with whatever filter was requested, so a member asking for a colleague's entries gets an
empty page — no leak, and no special-cased error to get wrong.

Active projects gate *new* time only:

```
starting a timer or recording a manual entry  →  project must be active
moving an existing entry to another project   →  archived is fine
```

Correcting which project last quarter's work belongs to is exactly the case where the right
answer is a project nobody is on any more. And a timer already running when its project is
archived **keeps running** and can be stopped normally — archiving stops new time being
tracked, it does not reach in and end work in progress.

### Listing

```
GET .../time-entries?page=0&size=50&projectId=…&projectCategoryId=…&userId=…&from=…&to=…&billable=true
```

Ordering is fixed server-side: `startedAt DESC, id DESC`. The tiebreaker is not decoration —
without it, entries sharing a timestamp could shuffle between pages and a client would see one
twice and another never. `from` is inclusive and `to` exclusive, both matched against
`startedAt`, so consecutive periods tile without double-counting a boundary entry.

Pagination is `page`/`size`, capped at 200 and defaulting to 50; out-of-range values are
clamped rather than rejected. The response is a plain envelope rather than Spring Data's
`Page`, whose JSON is an implementation detail:

```json
{ "content": [ … ], "page": 0, "size": 50, "totalElements": 128, "totalPages": 3 }
```

### Historical data

Time entries are what a customer eventually invoices from, and they outlive everything
around them. Removing someone from a project, removing them from the organization, closing
their account, archiving the project — **none of these touch a time entry**.

That holds for two structural reasons, not by convention. `time_entries` has no foreign key
to `project_members` or `organization_members`, so the cascades on those link tables cannot
reach it. And its own three foreign keys — to organizations, projects and users — all
*restrict* rather than cascade, so a hard delete of any parent fails loudly instead of
silently taking billable history with it. Nothing in the application hard-deletes those
parents, which is exactly why the constraint is worth having: it guards the path nobody
intends to take.

`DELETE` on an entry is a **soft delete**. Hard deletion was the alternative and was
rejected: a mis-clicked delete that irreversibly removes a month of billable work is a
support incident with no recovery path, whereas a tombstone is one `UPDATE` away from being
undone. That is deliberately not an audit system — there is no record of who deleted an entry
or why, and no restore endpoint. Deleting a *running* timer frees the one-timer slot
immediately, so a mistaken start does not lock someone out of the right one.

### Concurrency

Two simultaneous `POST .../timer` requests can both pass a "is a timer running?" check before
either commits. The application performs that check anyway — it produces a clean `409` in the
ordinary case — but the guarantee is a partial unique index:

```sql
CREATE UNIQUE INDEX ux_time_entries_running_per_user_organization
    ON time_entries (organization_id, user_id)
    WHERE ended_at IS NULL AND deleted_at IS NULL;
```

The losing insert violates it, and the violation is translated back into the same `409` the
pre-check would have produced. The pre-check is the manners; the index is the guarantee.

## Layout

Code is organised by business domain, not by technical layer:

```
com.tictac.io
├── common/          API error handling
│   ├── security/    CurrentUser, SecureToken
│   └── ratelimit/   throttling for the auth endpoints
├── user/            the user entity, ActiveUser, GET/DELETE /api/users/me
├── organization/    organizations, memberships, roles, invitations, tenant-scoped authorization
├── project/         projects, categories, assignments, project-scoped authorization
├── timetracking/    time entries, the timer, entry-scoped authorization
└── authentication/  registration, login, refresh, logout, HTTP security
    ├── token/       JWT issuing/decoding, refresh-token state and cleanup
    └── oauth/       Google registration, identity linking, sign-in handoff
```

Database migrations live in `src/main/resources/db/migration` and are the single source of
truth for the schema.

## Passwords

Hashed with Argon2id through Spring Security's `Argon2PasswordEncoder`, wrapped in a
`DelegatingPasswordEncoder` so every stored hash carries an algorithm prefix
(`{argon2}$argon2id$…`). That prefix is what allows the parameters — or the algorithm — to
change later without invalidating existing passwords.
