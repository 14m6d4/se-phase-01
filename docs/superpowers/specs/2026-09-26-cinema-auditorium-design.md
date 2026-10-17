# Cinema and Auditorium Catalogue — Design (#2)

## Purpose and scope

Admins manage physical Cinemas and their Auditoriums; visitors browse them and inspect immutable, generated Seat layouts. Deliver the endpoints and acceptance criteria of issue #2 and `docs/spec.md` without adding Showtime behavior (#3). Movie (#6) is already merged. Domain names follow `GLOSSARY.md`.

## API and authorization

- Cinema: `POST /api/cinemas` (201), `GET /api/cinemas`, `GET /api/cinemas/{id}`, `PUT /api/cinemas/{id}`, `DELETE /api/cinemas/{id}` (204). Create/update body: `name`, `address`; responses include `id`, `name`, `address`.
- Auditorium: `POST /api/cinemas/{id}/auditoriums` (201), `GET /api/cinemas/{id}/auditoriums`, `GET /api/auditoriums/{id}`, `DELETE /api/auditoriums/{id}` (204). Create body: `name`, `rows`, `seatsPerRow`, `vipRows`, `coupleRows`. Responses include `id`, `cinemaId`, `name`, `rows`, `seatsPerRow`; detail also includes `seats`, each with `id`, `row`, `number`, `label`, `type`. Lists omit seats.
- `vipRows`/`coupleRows` are row-letter lists (`["A", "B"]`); neither may contain the same row; unspecified rows are `STANDARD`. The row letter is uppercase and within the generated range. A `COUPLE` Seat is one sellable unit, not two physical Seat records.
- `rows` is 1–26; `seatsPerRow` is positive. Required names/addresses are nonblank; names fit 255 characters, address is text. Missing or invalid fields return 400 `application/problem+json` using Bean Validation. Nonexistent Cinema/Auditorium IDs return 404; duplicate name within one Cinema or deleting a Cinema containing Auditoriums returns 409.
- `SecurityConfig` already allows public catalogue GETs and restricts writes to ADMIN. Add `@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)` to each write so OpenAPI shows the lock. No access logic in controllers.

## Domain and flow

Keep business models, services, repository interfaces and layout generation in `business` with only JDK dependencies (ADR 0001). `CinemaService` owns CRUD and the dependent-Auditorium delete rule. `AuditoriumService` checks the parent exists, validates layout and name uniqueness, then generates row-major Seats: A1, A2, …, B1; every Seat gets a stable UUID. There is no Auditorium update endpoint: layout is immutable. Reject invalid row lists (out-of-range, duplicated, overlapping) before saving; business-level guard protects non-HTTP callers too. Follow Movie's controller DTOs and exception handling. Wire services in `BusinessConfig`.

Persist with `cinema`, `auditorium`, `seat` tables in Flyway V3, using app-generated UUID keys. Enforce `UNIQUE(cinema_id, name)` and `UNIQUE(auditorium_id, row_letter, seat_number)` in PostgreSQL; Auditorium's Cinema FK and Seat's Auditorium FK prevent orphans. Keep Seat type as `STANDARD`, `VIP`, `COUPLE` in Java and DB constraint. The JPA adapters implement business repository interfaces, map business models to package-private entities, and own transaction boundaries. Save Auditorium and all its Seats atomically; use a database uniqueness backstop to turn concurrent duplicate names into domain conflict (409). Deleting a Cinema with Auditorium children must produce 409 even on a race. No Spring/JPA types enter business code.

## Follow-up boundary with #3

The Showtime issue adds a `showtime.auditorium_id` FK and the Auditorium deletion check, just as it adds the Movie deletion check. Keep the `AuditoriumService.delete` → `AuditoriumRepository.deleteById` seam easy to extend. The #3 owner should place the new dependant check in the service (with a DB FK as race backstop) and extend the adapter's constraint translation. Neither check nor a Showtime stub belongs in #2.

## Verification and delivery

- Unit tests: row-major labels/types, `COUPLE` single-unit semantics, duplicate/overlapping/out-of-range rows and invalid dimensions.
- Integration tests against Testcontainers PostgreSQL: admin Cinema and Auditorium CRUD, public reads with seats, duplicate Auditorium name 409 (same Cinema only), Cinema-with-Auditorium delete 409, unknown IDs 404, unauthenticated/customer write rejection, OpenAPI read/write security markers. Update the Account test which currently expects admin `POST /api/cinemas` to return 404.
- Run `./mvnw verify` with Docker active. Commit in small conventional slices, push `feat/2-cinema-auditorium`, create PR into `main` with `Closes #2`. Do not merge.
