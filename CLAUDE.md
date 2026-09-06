# CLAUDE.md

Context for Claude Code working in this repository. Read this before changing anything.

---

## 1. What this is

Backend for a **cloud-first time-tracking SaaS** (a simpler, cheaper Toggl/Clockify), aimed
at small and medium companies in the US, Europe and the UAE. JSON REST API only — the
Next.js frontend lives in a **separate repository** and must never be added here.

The customer is the **organisation**, not the individual. One organisation → one
subscription → a seat quantity. Multi-tenancy and tenant isolation are security
requirements, not UI concerns: a user must never reach another organisation's data by
editing an id in a request.

None of that exists yet. See §9 for what is deliberately not built.

---

## 2. Stack, and one decision that keeps resurfacing

Kotlin · Spring Boot 4.1.1 · Java 21 · PostgreSQL 17 · Flyway · **Maven** · Docker ·
JUnit 5 · Testcontainers. Modular monolith. No microservices, Kafka, Redis, Kubernetes,
Elasticsearch, CQRS or event sourcing without a concrete requirement.

> **The build tool is Maven, not Gradle.** The product brief says "Gradle Kotlin DSL" and
> task prompts sometimes repeat it. On 2025-09-05 the user was asked directly and chose to
> keep the existing Maven scaffold. Treat that as settled — do not re-raise it, and do not
> propose converting. Kotlin is likewise settled; a passing mention of "Java" means the JVM
> target, not the language.

Group `com.tictac`, artifact `io`, package `com.tictac.io`.

---

## 3. Layout

Organised by business domain, never by technical layer.

```
com.tictac.io
├── common/
│   ├── ApiExceptionHandler.kt      RFC 9457 problem+json for the whole API
│   ├── security/CurrentUser.kt     the caller's id, from the verified token only
│   └── ratelimit/                  token bucket + filter for /api/auth
├── user/
│   ├── User.kt, UserRepository.kt
│   ├── ActiveUser.kt               resolves the token to a live account
│   ├── AccountClosureService.kt    DELETE /api/users/me, and the ownership guard on it
│   ├── EmailNormalization.kt       shared by registration and login
│   └── CurrentUserController.kt    GET + DELETE /api/users/me
├── organization/
│   ├── Organization*.kt            entity, repository, DTOs, controllers
│   ├── OrganizationRole.kt         OWNER/ADMIN/MEMBER + the whole membership policy
│   ├── OrganizationAccess.kt       THE tenant-scoped authorisation gate
│   ├── OrganizationOwnershipService.kt  the only operation that moves OWNER
│   └── Organization*Service.kt     creation, settings, membership management
├── project/
│   ├── Project*.kt                 entity, repository, DTOs, controllers
│   ├── ProjectAccess.kt            project-scoped gate, layered on OrganizationAccess
│   └── Project*Service.kt          creation, settings, assignment
└── authentication/
    ├── SecurityConfig.kt           two filter chains (public POSTs, default-deny)
    ├── PasswordEncoderConfig.kt
    ├── Registration*.kt            POST /api/auth/register
    ├── Authentication*.kt          login / refresh / logout
    ├── token/                      JWT issuing+decoding, refresh-token state, cleanup job
    └── oauth/                      Google registration, identity linking, sign-in handoff
```

Migrations in `src/main/resources/db/migration`, and they are the **only** source of truth
for the schema. `spring.jpa.hibernate.ddl-auto=validate` — Hibernate never creates or alters
anything, and startup fails if a mapping and a migration have drifted apart.

---

## 4. Schema

| Table | Purpose |
| --- | --- |
| `users` | `id`, names, unique `email`, nullable `password_hash`, `created_at`, `deleted_at` (soft delete) |
| `refresh_tokens` | server-side refresh state: `token_hash`, `expires_at`, `revoked_at`, `replaced_by_id` |
| `user_identities` | `(user_id, provider, provider_user_id)` — external logins |
| `oauth_login_codes` | single-use codes handing an OAuth sign-in to the frontend |
| `organizations` | `organization_id`, `organization_name`, `created_at`, `deleted_at` (soft delete) |
| `organization_members` | `(organization_id, user_id)` unique, `role`; one OWNER per org via a partial unique index |
| `projects` | `project_id`, `organization_id`, `project_name`, `description`, `is_active` (archive flag, not a soft delete), `created_at`, `updated_at` |
| `project_members` | `(project_id, user_id)` unique - assignment of an organisation member to a project. No role, no organisation id |

Designed but **not yet implemented**: `subscriptions`. The agreed DBML for it lives in the
product brief; treat it as the source of truth and do not redesign it. The organization
tables follow that DBML's column names (`organization_id`, `organization_name`) rather than
the `id`/`name` shorthand `users` uses — the entity maps them back, so the
inconsistency stops at the schema boundary.

Conventions: UUID primary keys via `@GeneratedValue(strategy = GenerationType.UUID)`;
`timestamptz` (not `timestamp`) because the product is global; `varchar` lengths declared in
both the migration and the entity so `validate` passes.

---

## 5. Authentication — the model, and why it is shaped this way

Stateless bearer tokens. No HTTP session for the API; the access token is the session.

```
POST /api/auth/register            public
POST /api/auth/login               public   → access + refresh token
POST /api/auth/refresh             public   → rotates both
POST /api/auth/logout              public   → revokes a refresh token
POST /api/auth/oauth/exchange      public   → one-time code → same token pair
GET  /oauth2/authorization/google  public   → starts the Google flow
everything else                    requires a valid access token (default-deny)
```

**Three filter chains, and the order matters.**

- `@Order(0)` `/oauth2/**` + `/login/oauth2/**` — permits a session (the authorization
  request must survive the round trip to Google); no bearer support.
- `@Order(1)` the public POSTs, matched **by method and path** so `GET /api/auth/register`
  still falls through to default-deny. **No bearer-token support on purpose**: clients
  routinely attach their access token to every request, and if this chain parsed it, a
  *stale* token would fail authentication before `/logout` could run — exactly when logout
  matters. Here the header is ignored.
- `@Order(2)` everything else: `anyRequest().authenticated()` + `oauth2ResourceServer`.

**Passwords.** Argon2id via `Argon2PasswordEncoder` inside a `DelegatingPasswordEncoder`, so
every hash carries a `{argon2}` prefix and the parameters (or the algorithm) can change later
without invalidating existing passwords. Needs BouncyCastle on the classpath.

**Access tokens.** HS256, 15 min, claims are exactly `iss sub iat exp jti` — nothing else. A
JWT is signed, not encrypted; names, emails and future roles stay out of it. Clock skew is 5s
(we are our own issuer). `JWT_SECRET` and `JWT_ISSUER` are required with no defaults, and the
issuer must be an absolute URI.

**Refresh tokens.** Opaque 256-bit CSPRNG values, *not* JWTs, stored as SHA-256. SHA-256
rather than Argon2 is deliberate: nothing to brute-force in a random 256-bit value, and a
slow hash would make every refresh expensive. Every refresh rotates.

### Invariants — do not undo these without deciding to

These each exist because of a specific attack, and each has a test. If a change makes one of
them fail, the change is wrong, not the test.

1. **Entity `toString()` never includes a secret** (`passwordHash`, `tokenHash`, `codeHash`).
   Entities end up in log lines and exception messages; that is how hashes leak. Entities are
   plain classes, never `data class`, partly for this reason.
2. **Login always performs an Argon2 comparison**, against a decoy hash when no account
   exists. Otherwise an unknown address returns in microseconds and a known one takes ~40ms —
   enough to enumerate accounts. Unknown email, wrong password, deleted account and
   null-hash account all return byte-identical 401s.
3. **Refresh reuse detection keys off `replaced_by_id`, not "is revoked".** A token that was
   *rotated away* should never be presented again, so seeing it means the token was copied →
   revoke every session for that user. A token revoked by *logout* has no replacement;
   replaying one is client confusion and must **not** sign the user out elsewhere.
4. **The reuse revocation runs in `REQUIRES_NEW`, in its own bean** (`RefreshTokenRevoker`).
   It revokes and *then* fails the request; sharing the caller's transaction would roll the
   revocation back along with the rejected refresh. The separate bean is required because a
   `@Transactional` call on `this` bypasses the proxy.
5. **OAuth linking requires a verified email** to create *or* link. Attaching an unverified
   address to an existing account is account takeover; creating one on it is address
   squatting. The gate applies only to establishing the link — afterwards sign-in goes by
   `sub` and never consults the email again.
6. **OAuth matches on the provider's `sub`, never the email**, so changing a Google address
   keeps the account and a provider cannot rewrite `users.email` by side effect.
7. **`CurrentUser` reads only the `sub` of a signature-verified JWT.** Never a path variable,
   body field or header. No endpoint accepts a client-supplied user id. For anything acting
   *on* the account, use `ActiveUser.require()`, which also checks the account still exists —
   access tokens are not revocable within their 15-minute life, and that lookup is what closes
   the window.
8. **OAuth tokens never reach the frontend.** The redirect carries a single-use 60s code
   (SHA-256 at rest), exchanged over POST for the normal token pair. Putting tokens in the
   redirect URL would write a 30-day refresh token into browser history and any log of the
   landing URL.
9. **Redirect URIs come from configuration, never the request** — otherwise open redirect.
10. **`saveAndFlush`, not `save`,** wherever a unique-constraint violation must be catchable
    inside the method rather than at commit.
11. **Every organisation-scoped operation opens with `OrganizationAccess.require(...)`.** The
    organisation id comes from the URL and is untrusted; it is safe only because it is used
    as one half of a membership lookup whose other half comes from the token. Nothing else
    queries by organisation id — an endpoint that forgets the gate is a
    cross-tenant leak, and one unforgettable place beats a check copied into every controller.
12. **A non-member gets 404, a member with the wrong role gets 403.** Ids travel; a 403/404
    split would turn a leaked organisation id into an existence oracle for that tenant. A
    member already knows the organisation exists, so 403 there costs nothing.
13. **Creating an organisation and its OWNER membership is one transaction.** An organisation
    with no owner is unfixable through the API — granting a role requires
    already being a member of it.
14. **`OrganizationRole.outranks` / `canAssign` are the entire membership policy.** No role
    outranks itself or the OWNER, and OWNER is never assignable; that is what makes
    self-promotion, admin-on-admin action and an ownerless organisation all fail closed.
15. **Ownership moves only through `OrganizationOwnershipService`.** It is the single
    exception to invariant 14, and it demotes the outgoing owner in the same transaction as
    it promotes the incoming one. Two role edits could not do this without passing through
    two owners or none.
16. **The demote is flushed before the promote.** The partial unique index permits one OWNER
    row per organisation at any instant, so the order is load-bearing: promoting first
    violates it on every transfer. Two `saveAndFlush` calls pin it — mutating both
    and letting one flush sort it out leaves the order to Hibernate's action queue.
17. **An active organisation is never owned by a closed account.** Enforced from both ends:
    account closure refuses while the caller owns one, and ownership transfer refuses a
    closed target. The two interlock on a `SELECT ... FOR UPDATE` of the `users` row, so they
    cannot both read a stale answer and then both act on it.
18. **A project is always resolved by `(project id, organisation id)` together**, via
    `ProjectAccess`. Never `findById` on a project. A project id from another tenant is a
    valid UUID that exists in the table; looking it up by both ids is what makes it
    unreachable, with no second step anyone can forget.
19. **Organisation membership grants nothing at project level.** An organisation MEMBER sees
    and reaches only the projects they are assigned to; assignment is always an explicit act
    by an OWNER or ADMIN. Administrators see every project without being assigned — being
    able to administer a project is not the same as being on it.
20. **Project visibility is checked before the role gate.** An unassigned MEMBER gets 404, an
    assigned one lacking the role gets 403. Reversing the order would let the refusal confirm
    a project exists to someone with no way to know it does.
21. **Removing an organisation member deletes their project assignments in that organisation,
    in the same transaction.** "A project member is also an organisation member" cannot be a
    foreign key - `project_members` reaches the organisation only through
    `projects.organization_id` - so it is enforced in `OrganizationMembershipService`.
    Account *closure* deliberately does not cascade: it does not break the invariant.
22. **Projects are archived, never deleted** (`is_active`). Time entries will point at them,
    and an archived project still has to render every historical entry that references it.

### OAuth flow, in one picture

```
browser → Google → callback → (backend ⇄ Google: code + client secret → ID token)
        → Spring verifies signature / aud / iss / exp   ← not our code
        → GoogleIdentityExtractor → OAuthAuthenticationService → users.id   ← our code
        → one-time code → frontend → POST /exchange → our access + refresh tokens
```

Google only. Registrations are built in code (`ClientRegistrations`) from a client id and
secret — no `issuer-uri`, deliberately, since that triggers OIDC discovery **at startup** and
would make a Google outage a boot failure. A second provider needs a new `OAuthProvider`
constant and its own extractor, because "is this email verified?" is a different claim at
every provider (Google returns `email_verified`; Microsoft does not issue it at all).

With no `GOOGLE_CLIENT_ID` set, no registration exists, the OAuth chain is never created, and
the app runs fine. Keep it that way — a developer must be able to clone and run.

---

## 6. Configuration

Everything is env-driven with local defaults, except secrets. `.env` is git-ignored;
`.env.example` is the committed template. **Spring Boot does not read `.env`** — Docker
Compose does. For the app: `set -a; source .env; set +a`.

Required with **no default** (the app refuses to start): `JWT_SECRET` (≥32 bytes),
`JWT_ISSUER` (absolute URI). Fail-closed is intentional.

Railway note: it injects a `postgresql://` URL, which is *not* a valid JDBC URL. `DATABASE_URL`
must be the `jdbc:postgresql://…` form.

---

## 7. Testing

```bash
docker compose up -d                 # PostgreSQL 17 on :5432
set -a; source .env; set +a
./mvnw spring-boot:run
./mvnw clean verify                  # 340 tests; Docker must be running
```

- **Real PostgreSQL via Testcontainers.** No in-memory substitute — it would not catch a
  mismatch between a migration and a mapping.
- `PostgresIntegrationTest` is the base: `@SpringBootTest` + MockMvc + container, and a
  `@BeforeEach` that clears all eight tables children-first. `AuthenticatedApiTest` extends it
  with register/login helpers that go through the **real** endpoints and filter chain.
- All base-derived tests share one Spring context (and one container). A test that overrides
  properties gets its own context and its own container — currently
  `RateLimitedAuthenticationIntegrationTest`, `OAuth2ProviderWiringIntegrationTest` and
  `OrganizationAtomicityIntegrationTest` (a `@MockitoSpyBean`). All three earn it: they
  prove wiring or transaction behaviour that unit tests cannot. Put any new
  rollback test in the existing atomicity class rather than starting another spy context.
- Concurrency is tested by calling the service from two threads, not through MockMvc: each
  needs its own transaction, and `SecurityContextHolder` is thread-local. See
  `OrganizationOwnershipConcurrencyIntegrationTest`.
- `OrganizationApiTest` extends `AuthenticatedApiTest` with organisation helpers and adds no
  bean overrides, so the organisation tests stay in the shared context.
- **Never mock away Spring Security** for authentication tests.
- The external provider is stubbed at the `OidcUser` / `OAuth2AuthenticationToken` boundary —
  i.e. from verified claims onward. Nothing contacts Google.
- Test config is `src/test/resources/application-test.properties` + `@ActiveProfiles("test")`,
  **not** `application.properties`. See §8.

---

## 8. Gotchas that cost real time here

Spring Boot 4 / Kotlin specifics, all discovered the hard way:

1. **Boot 4 ships auto-configuration in per-technology modules.** `flyway-core` alone does
   nothing — you also need `org.springframework.boot:spring-boot-flyway`. Same pattern
   elsewhere.
2. **Testcontainers 2.x renamed its modules**: `testcontainers-postgresql`,
   `testcontainers-junit-jupiter` (not `postgresql` / `junit-jupiter`). New class package is
   `org.testcontainers.postgresql.PostgreSQLContainer`.
3. `@AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure`.
4. **BouncyCastle is not in the Boot 4 BOM** — pin `bcprov-jdk18on` explicitly.
5. **Kotlin allows nested block comments.** `/api/auth/**` inside a KDoc opens a comment that
   never closes; the error is a baffling "Unclosed comment" at EOF. Write `/api/auth` instead.
6. **The all-open plugin forbids `private set` on entity properties** (they become open).
7. `PasswordEncoder.encode` returns a platform type that infers as `String?` under
   `-Xjsr305=strict`; wrap in `requireNotNull`.
8. **A test `application.properties` shadows the main one completely** — same classpath name,
   first match wins — so tests silently stop seeing production defaults. Use a
   profile-specific overlay instead. This was already causing a real bug.
9. **`mvn test` without `clean` leaves stale files in `target/test-classes`** after a rename,
   which then shadow the new ones. When something makes no sense after a rename, `clean`.
10. **`@ConditionalOnBean` is unreliable in user configuration** — user config is processed
    before auto-configuration, so the bean does not exist yet. Use a `Condition` that reads
    the `Environment` (see `OAuth2ProviderConfigured`).
11. **Spring Boot rejects an OAuth registration with a blank `client-id`**, so
    `${GOOGLE_CLIENT_ID:}` under `spring.security.oauth2.client.registration.*` breaks
    startup. That is why registrations are built in code from our own `security.oauth2.*`
    properties.
12. **`Jwt.getIssuer()` throws** unless the issuer is an absolute URI, even though the JWT
    spec allows any StringOrURI.
13. **Heredocs in this shell fail on some content.** Use the Write tool for Kotlin files;
    heredocs are fine for SQL and properties. `python - <<'EOF'` is reliable for patching, but
    a `\` at end-of-line inside a Python string is a line continuation and will silently eat
    your shell-continuation backslashes in generated Markdown.

---

## 9. Deliberately not built yet

Do not add these speculatively; each is its own task.

Subscriptions · Stripe · seats and licensing · clients · tasks · time entries · reports ·
CSV export · email sending (Resend) · **CORS** · frontend code of any kind.

Organisations, memberships and organisation roles now exist (see the invariants in §5).
Ownership transfer and account closure exist too (invariants 15-17), and so do projects and
project assignment (invariants 18-22). What is *not* built on top of them: invitations,
leaving an organisation or a project voluntarily, per-project roles, and any permission model
finer than the three organisation roles.

Known open items in what *is* built:

- No "link a provider from settings", no unlink, no list of linked identities. An
  unverified-email collision is currently a dead end for the user.
- Access tokens are not revocable within their 15-minute life (no `jti` denylist — a
  deliberate call; a per-user token epoch is the likely future answer). `ActiveUser.require()`
  is the mitigation at point of use.
- Rate limiting is in-memory and per-instance, covering `/api/auth` only (not `/oauth2/**`).
  It is the inner of two layers; Cloudflare is expected in front. Set
  `RATE_LIMIT_CLIENT_IP_HEADER` only to a header the edge **overwrites** (`CF-Connecting-IP`),
  never `X-Forwarded-For` — a caller can prepend to that and earn a fresh bucket per request.
- No per-account login throttling (IP limiting does not stop slow credential stuffing).
- UUIDv4 primary keys are random, which hurts index locality. Irrelevant for `users`; decide
  before `time_entries`, where the row count will actually live.
- OAuth-created users can have a blank `last_name` if Google returns no family name.
- No invitations, so the only way into an organisation is founding one. Membership rows for
  anyone else currently have to be seeded directly (the tests do this deliberately).
- Account closure keeps non-owner membership rows, by decision (see `AccountClosureService`).
  They are inert and invisible, but an organisation's member count and its
  `organization_members` row count therefore differ. Seat counting must go through
  `users.deleted_at`; do not count rows.
- No "leave organisation" for a non-owner. An admin can remove them, but they cannot walk out
  on their own, and the only self-service exit is closing the account entirely.
- Closure is refused, never queued. There is no grace period, no scheduled purge, and no
  restore — a closed account is soft-deleted forever and its email stays taken.
- A soft-deleted organisation may be owned by a closed account. Deliberate: it is unreachable
  either way, and blocking closure on it would be a dead end since transfer refuses deleted
  organisations too. Revisit if organisation restore is ever built.
- Organisation soft delete leaves everything else in place, including memberships and
  projects. There is no restore and no purge.
- Projects have no per-project roles. Everyone assigned to a project has the same standing in
  it, and what they may *do* to it comes from their organisation role. Adding a project role
  now would be a permission framework built ahead of any requirement for one.
- The project listing filters `active` in memory, not in SQL. Fine at tens of projects per
  organisation; move it into the query before that assumption stops holding.
- An organisation ADMIN can archive a project they are not on, and nothing warns them it has
  work recorded against it. Worth revisiting once time entries exist.
- `project_members` has no `deleted_at`, matching `organization_members`. A closed account
  keeps its assignments; they are inert and filtered out of every listing.
- `logging` never contains passwords, hashes or tokens. Keep it that way.

---

## 10. How to work in this repo

- **Do not `git add`, commit or push unless explicitly asked.** The user manages their own
  staging and commits.
- Tests are mandatory with every change, including the unhappy paths. Security-sensitive
  behaviour needs a test that would fail if the protection were removed.
- Run `./mvnw clean verify` before reporting done, and say plainly if anything fails.
- Prefer explaining a non-obvious decision in a comment *at the code*, saying **why**, not
  what. The existing comments are dense on purpose — match that density, and keep them
  truthful if you change the code they describe.
- Do not over-engineer, and do not add an abstraction with one implementation. The
  `OidcIdentityExtractor` interface was removed for exactly this reason when Microsoft was
  dropped.
- When a task prompt conflicts with a decision the user made in conversation, say so in one
  sentence and follow the decision — do not silently do either.
