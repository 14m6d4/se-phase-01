# Phase 1 Specification

Backend service for a cinema chain: admins manage the catalogue, customers hold and pay for seats. Terms in **bold** are defined in [`CONTEXT.md`](../CONTEXT.md); architectural decisions live in [`docs/adr/`](adr/).

## Assignment requirements → where they are met

| Requirement | How |
|---|---|
| REST + JSON, POST/GET/DELETE | [Endpoints](#endpoints) |
| OpenAPI/Swagger | springdoc, Swagger UI at `/swagger-ui.html` |
| Layered architecture, business layer free of web/DB imports | `api` → `business` → `persistence`, enforced by an ArchUnit test ([ADR 0001](adr/0001-framework-free-business-layer.md)) |
| Repository / DAL with ORM | Spring Data JPA (Hibernate) adapters implementing business-layer repository interfaces |
| Login, ≥1 authenticated GET and POST | JWT login; `GET /api/bookings`, `POST /api/bookings`, … |
| Auth via framework middleware | Spring Security filter chain (`BearerTokenAuthenticationFilter`), rules declared once |
| Docker | Multi-stage `Dockerfile`, `docker-compose.yml`, image published to GHCR |
| Load test on Kaggle CPU | `loadtest/` notebook + k6 ([ADR 0003](adr/0003-load-test-on-kaggle-without-docker.md)) |

## Tech stack

Java 21, Spring Boot 4, Spring Security (OAuth2 resource server, HMAC JWT), Spring Data JPA, PostgreSQL, Flyway, springdoc-openapi 3, Bean Validation, Actuator, Testcontainers, ArchUnit, Maven (wrapper), Docker (`eclipse-temurin:21-jre-alpine`), GitHub Actions, k6.

## Architecture

Single Maven module, packages by layer under `vn.edu.uet.cinema`:

| Package | Contains | May depend on |
|---|---|---|
| `api` | Controllers, request/response DTOs, exception → `ProblemDetail` mapping | `business`, Spring Web |
| `business` | Domain models, services (use cases), repository interfaces, domain exceptions | `java.*` only |
| `persistence` | JPA entities, Spring Data repositories, adapters implementing `business` repository interfaces | `business`, JPA |
| `config` | Security, bean wiring (`@Bean` for services), OpenAPI | everything |

Each use case saves exactly one aggregate; the persistence adapter owns the transaction. No `@Transactional` in `business`.

## Business rules

### Catalogue
- A **Cinema** has a `name` and `address`.
- An **Auditorium** belongs to one Cinema; its `name` is unique within that Cinema. Its layout is generated from `rows` (≤ 26, lettered A…) × `seatsPerRow`, with `vipRows` and `coupleRows` listing rows of those **Seat Types**; other rows are Standard. The layout is immutable.
- A **Movie** has `title`, `description`, `durationMinutes`, `genre`, `ageRating` (`P`, `K`, `T13`, `T16`, `T18`; display only), `releaseDate`, `posterUrl`.
- A **Showtime** has `movieId`, `auditoriumId`, `startsAt`, `basePrice`. `endsAt = startsAt + durationMinutes + 15 min` (cleaning) is fixed at creation; later Movie edits do not change it.
- Showtimes in the same Auditorium may not overlap on `[startsAt, endsAt)`.
- Deletes are hard deletes, refused with `409` while dependants exist: Cinema with Auditoriums, Auditorium with Showtimes, Movie with Showtimes, Showtime with a Confirmed or live Pending Booking (lapsed Holds are deleted along with the Showtime); such a Showtime can be Cancelled instead.

### Showtime cancellation
- An Admin can **Cancel** a Showtime because of a problem on the cinema's side, from creation until its `endsAt`; later, or if already Cancelled → `409`. Cancelled is final.
- A **Cancellation Reason** is required: `EQUIPMENT_FAILURE`, `POWER_OUTAGE`, `EMERGENCY` or `OTHER`, plus a `note` (≤ 200 characters) that `OTHER` requires.
- Its Confirmed Bookings become **Refunded** (full `totalPrice`, simulated), its Pending Bookings are deleted, and it can no longer be booked.
- A Cancelled Showtime is left out of `GET /showtimes`, but `GET /showtimes/{id}` still returns it with its status and Cancellation Reason.
- It no longer takes part in the no-overlap rule: a replacement Showtime may use its time slot.

### Pricing
- Standard = **Base Price**; VIP = Base Price + 20,000; Couple = 2 × Base Price + 10,000. Surcharges are constants.
- Money is an integer amount of VND.
- Seat prices are copied into the Booking when it is created; later changes don't affect it.

### Booking
- One **Booking** = one Showtime, 1–8 Seats (a Couple Seat counts as one), all in that Showtime's Auditorium. Duplicate `seatIds` → `400`.
- Cannot book a Showtime that has already started.
- A new Booking is **Pending** with a **Hold** of 10 minutes (`expiresAt`).
- A Customer may have at most one live Pending Booking; booking again while it is live → `409` naming that Booking's id.
- `POST /bookings/{id}/payment` (simulated, always succeeds) makes it **Confirmed** if the Hold is still live, else `409`. The Hold alone decides: payment after the Showtime has started is allowed. Paying an already Confirmed Booking returns it unchanged (`200`), so a client can retry safely.
- A Pending Booking can be abandoned (`DELETE`): it is removed and its Seats freed. A Confirmed Booking cannot be cancelled or refunded by the Customer (`409`); only Cancelling its Showtime makes it Refunded.
- A Pending Booking past `expiresAt` is **Expired**: shown as such, cannot be paid, and removed the next time anyone books one of its Seats ([ADR 0004](adr/0004-seat-contention-via-unique-constraint-and-lazy-holds.md)).
- Double booking is prevented by `UNIQUE(showtime_id, seat_id)` on booked seats; the loser gets `409` listing which of the requested `seatIds` are taken. A booking that loses a database deadlock is retried once before reporting anything.
- A Customer only sees their own Bookings; someone else's Booking id returns `404`.

### Time
The whole chain runs on `Asia/Ho_Chi_Minh`. Timestamps are stored as `timestamptz` and exchanged as ISO-8601 with offset. `?date=` means 00:00–24:00 Vietnam time.

## Security

- Roles: `CUSTOMER` (self-registered) and `ADMIN` (created at startup from `ADMIN_EMAIL`/`ADMIN_PASSWORD`, never registered through the API).
- `POST /api/auth/login` returns `{accessToken, tokenType, expiresIn}` (`tokenType` is `Bearer`, `expiresIn` in seconds); HS256 JWT, 1 hour, no refresh token. Secret from env `JWT_SECRET`.
- Passwords hashed with BCrypt.
- Public: auth endpoints, all catalogue `GET`s, Swagger, `/actuator/health`. Catalogue writes: `ADMIN`. `/api/bookings/**`: `CUSTOMER`. `/actuator/metrics`: `ADMIN`.

## Endpoints

All under `/api`. Errors are RFC 9457 `application/problem+json`.

| Resource | Method & path | Access |
|---|---|---|
| Auth | `POST /auth/register` `{email, password, fullName}` | public |
| | `POST /auth/login` `{email, password}` | public |
| | `GET /me` (own Account) | authenticated |
| Cinema | `POST /cinemas`, `PUT /cinemas/{id}`, `DELETE /cinemas/{id}` | ADMIN |
| | `GET /cinemas`, `GET /cinemas/{id}` | public |
| Auditorium | `POST /cinemas/{id}/auditoriums`, `DELETE /auditoriums/{id}` | ADMIN |
| | `GET /cinemas/{id}/auditoriums`, `GET /auditoriums/{id}` (with seats) | public |
| Movie | `POST /movies`, `PUT /movies/{id}`, `DELETE /movies/{id}` | ADMIN |
| | `GET /movies`, `GET /movies/{id}` | public |
| Showtime | `POST /showtimes`, `DELETE /showtimes/{id}`, `POST /showtimes/{id}/cancellation` `{reason, note}` | ADMIN |
| | `GET /showtimes?date=&cinemaId=&movieId=` (`date` required) | public |
| | `GET /showtimes/{id}`, `GET /showtimes/{id}/seats` (seat map: status `AVAILABLE`/`HELD`/`SOLD` + price) | public |
| Booking | `POST /bookings` `{showtimeId, seatIds}` | CUSTOMER |
| | `GET /bookings` (own, newest first), `GET /bookings/{id}` | CUSTOMER |
| | `POST /bookings/{id}/payment`, `DELETE /bookings/{id}` | CUSTOMER |

No pagination in phase 1: `GET /showtimes` is bounded by `date`, the other lists are small.

## Load test (baseline)

On a Kaggle CPU notebook ([ADR 0003](adr/0003-load-test-on-kaggle-without-docker.md)): install JDK 21 and PostgreSQL via `apt`, run the JAR, load `loadtest/seed.sql`, run k6.

- Seed: 3 Cinemas × 4 Auditoriums (10 × 12 seats), 20 Movies, 7 days of Showtimes starting the day after the seed is loaded, 2,000 Customers sharing one pre-hashed password.
- Each scenario that needs a token logs in as many Customers as its `maxVUs`, once in `setup` (BCrypt would otherwise dominate).
- Scenarios: **browse** (showtimes by date → seat map), **book** (hold → pay), **contention** (many VUs on the same seats; afterwards SQL verifies no seat sold twice).
- `ramping-arrival-rate`; record RPS, p50/p95/p99 latency, error rate into `docs/loadtest-baseline.md`.

## Out of scope (phase 1)

Real payment, promotions, concessions, tickets/QR/check-in, refunds other than Showtime cancellation, out-of-service Seats (staff reseat on site), limits on holding Seats without paying, pagination, refresh tokens, email verification, hosting/CD beyond publishing the image.

## Workflow

Branch per issue, PR into `main` once CI (`mvn verify`) is green; no review required. Conventional Commits.
