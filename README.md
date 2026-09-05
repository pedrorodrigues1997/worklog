# io — backend

Backend for a cloud-first time-tracking SaaS. Kotlin + Spring Boot, PostgreSQL, modular
monolith organised by business domain.

The frontend lives in a separate repository. This service exposes a JSON REST API only.

## Prerequisites

- JDK 21
- Docker (for local PostgreSQL and for the Testcontainers-backed tests)

Maven itself is not required — use the bundled wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Start PostgreSQL

```bash
docker compose up -d
```

That starts PostgreSQL 17 on `localhost:5432` with database `io`, user `io`, password `io` —
the same values the application defaults to, so no configuration is needed for local work.

```bash
docker compose down     # stop
docker compose down -v  # stop and delete the data volume
```

## Run the backend

```bash
./mvnw spring-boot:run
```

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

The defaults exist so a developer can clone and run. In any deployed environment all three
must be set explicitly.

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

## Layout

Code is organised by business domain, not by technical layer:

```
com.tictac.io
├── common/          cross-cutting concerns (API error handling)
├── user/            the user entity and its repository
└── authentication/  registration, password encoding, HTTP security
```

Database migrations live in `src/main/resources/db/migration` and are the single source of
truth for the schema.

## Passwords

Hashed with Argon2id through Spring Security's `Argon2PasswordEncoder`, wrapped in a
`DelegatingPasswordEncoder` so every stored hash carries an algorithm prefix
(`{argon2}$argon2id$…`). That prefix is what allows the parameters — or the algorithm — to
change later without invalidating existing passwords.
