# Cinema Booking API

Backend service for a cinema chain, built for the Software Architecture course (phase 1). Admins manage cinemas, auditoriums, movies and showtimes; customers hold seats for a showtime and pay for them.

- **Specification:** [`docs/spec.md`](docs/spec.md) (business rules, endpoints, load test plan)
- **Domain glossary:** [`CONTEXT.md`](CONTEXT.md)
- **Architecture decisions:** [`docs/adr/`](docs/adr/)

## Tech stack

Java 21 · Spring Boot 4 · Spring Security (JWT) · Spring Data JPA / Hibernate · PostgreSQL 17 · Flyway · springdoc-openapi · Testcontainers · ArchUnit · Maven · Docker · GitHub Actions · k6

## Architecture

Layered architecture, one package per layer under `vn.edu.uet.cinema`:

```
 HTTP ──► api ──────────► business ◄────────── persistence ──► PostgreSQL
          controllers,     domain models,        JPA entities,
          DTOs, error      services, repository  Spring Data repositories,
          mapping          interfaces            adapters
                                ▲
          config ───────────────┘  security rules, bean wiring, OpenAPI
```

`api` may also read names owned by `config` (the JWT roles claim, the OpenAPI security scheme).

- `business` depends on nothing but the JDK: no Spring, no JPA, no web ([ADR 0001](docs/adr/0001-framework-free-business-layer.md)). It declares the repository interfaces it needs; `persistence` implements them.
- Authentication is done once, in Spring Security's filter chain (`config/SecurityConfig.java`), never inside controllers.
- `ArchitectureTest` fails the build if a layer rule is broken.

## Run with Docker

```bash
cp .env.example .env    # then edit the secrets in .env
docker compose up --build
```

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

Secrets are never stored in the repo: compose reads them from `.env` (git-ignored) or your shell, and refuses to start if one is missing. Log in as Admin with the `ADMIN_EMAIL` / `ADMIN_PASSWORD` from your `.env`. Every push to `main` also publishes the image as `ghcr.io/14m6d4/se-phase-01:latest`.

## Run locally (without Docker for the app)

Requires JDK 21 and a PostgreSQL database (`docker compose up db` is the easiest).

```bash
set -a; source .env; set +a   # export the variables from .env
./mvnw spring-boot:run
```

Alternatively, run `TestCinemaApplication` (in `src/test`) from your IDE: it starts PostgreSQL in a container automatically. You still need `JWT_SECRET` and `ADMIN_PASSWORD` in the run configuration's environment.

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/cinema` | JDBC URL |
| `DB_USERNAME` | `cinema` | Database user |
| `DB_PASSWORD` | none (required) | Database password |
| `JWT_SECRET` | none (required) | HS256 signing key, ≥ 32 bytes |
| `ADMIN_EMAIL` | `admin@cinema.local` | The Admin account, created or updated at startup |
| `ADMIN_PASSWORD` | none (required) | Its password; change it and restart to rotate |

## Using the API

```bash
# Register a customer
curl -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123","fullName":"Alice"}'

# Log in and call an authenticated endpoint
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}' | jq -r .accessToken)
curl localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
```

In Swagger UI, call `POST /api/auth/login`, then click **Authorize** and paste the `accessToken`.

Errors use [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`. `401` means missing or invalid token, `403` means wrong role, `409` means a business rule conflict.

## Tests

```bash
./mvnw verify
```

Docker must be running: integration tests start PostgreSQL with Testcontainers.

## Project structure

```
src/main/java/vn/edu/uet/cinema/
├── api/            REST controllers, DTOs, exception → ProblemDetail
├── business/       domain models, services, repository interfaces (framework-free)
├── persistence/    JPA entities, Spring Data repositories, adapters
└── config/         security, bean wiring, OpenAPI
src/main/resources/db/migration/   Flyway migrations
docs/                              spec, ADRs
```

## Contributing

- One branch per GitHub issue, merged into `main` through a PR once CI is green.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/).
- Use the vocabulary from [`CONTEXT.md`](CONTEXT.md) in code, issues and docs.
