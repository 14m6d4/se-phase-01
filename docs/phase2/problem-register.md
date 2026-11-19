# Phase 2 problem register

Every problem phase 2 may work on, in priority order. Each becomes one GitHub issue titled `<ID>: <title>`. Evidence comes from the code and from the phase 1 Kaggle baseline (`docs/loadtest-baseline.md`, commit `4d40ac3`). "Hypothesis" means it must be measured before it is fixed.

Priority rests on four questions, in this order: does it bear on the race condition at the core of booking, can it be measured on Kaggle, does other work depend on it, and what does it cost.

## Baseline to beat (highest step held, it/s, Run 1 / Run 2)

| Scenario | Held | Gave out first |
|---|---|---|
| account | 1,200 / 1,200 | Requests queued in the app (P5) |
| browse | 400 / 400 | Inconclusive: k6 at 92–93% of its core (P6) |
| book | 50 / none | Unknown; iowait 6–12% (P4) |
| contention | 200 / 400 | Seat-status query (P1, P2) |
| race | 200 / 300 | Seat-status query, lock waits (P1, P2) |

## Tier 0: setup

### T0: Rerun the baseline on the team's Kaggle account
- Labels: `tier:0`, `area:ops`, `needs-benchmark`. Status: task.
- Two versions of `main`, all scenarios, fixed parameters (ADR 0007). Every later result compares with this.

### O1: Observability too thin to diagnose P4 and P5
- Labels: `tier:0`, `area:ops`. Severity low. Status: confirmed.
- Evidence: the app exposes health and metrics only; no per-step latency for DB, commit or lock wait; no 409 count by reason.
- Direction: Micrometer timers around booking steps and commit; counters for 409 reasons; enable `track_io_timing` and `track_wal_io_timing` in the notebook.

### P6: k6 runs out of CPU before browse does
- Labels: `tier:0`, `area:perf`, `needs-benchmark`. Severity medium. Status: confirmed.
- Evidence: k6 at 92–93% of its core at 600 it/s (browse); the report flags ≥90%.
- Direction: lighten the browse script (discard unused bodies, fewer checks). Rerun the old code with the new script (ADR 0007).

## Tier 1: quick wins

### B1: A large basePrice makes the seat map return 500 for good
- Labels: `tier:1`, `area:bug`. Severity high. Status: confirmed.
- Evidence: creation checks only `basePrice > 0` (`ShowtimeService.create`, `@Positive`, V4 `CHECK`). `SeatPricing.price` uses `Math.addExact`/`multiplyExact` and `Booking.totalPrice` sums with `addExact`; `ApiExceptionHandler` has no handler for `ArithmeticException`.
- Direction: bound `basePrice` at creation so a Couple Seat and an eight-Seat total stay within `long`; API test for the bound.

### P2: Seat-status read and cascade delete may lack indexes
- Labels: `tier:1`, `area:perf`, `needs-benchmark`. Severity high. Status: resolved without a migration. EXPLAIN: the seat-status read scanned `booking` (52,933 rows removed, 3.4 ms); P1's rewrite uses `uq_booked_seat_showtime_seat` (0.49 ms). The cascade delete was never a problem: `booked_seat`'s primary key `(booking_id, seat_id)` leads with `booking_id`.
- Evidence: `BookingEntityRepository.findOccupiedSeats` filters `booking.showtime_id`, and V6 has no index led by it (`uq_booking_id_showtime` leads with `id`). `booked_seat` has no index led by `booking_id`, while `fk_booked_seat_booking` cascades deletes from `booking` (abandon, lapsed-Hold delete, cancel).
- Direction: `EXPLAIN (ANALYZE, BUFFERS)` on Kaggle after a full run; add a `V10` migration only for what the plans show.
- Measure: contention, race.

### P1: The 409 path re-reads every Seat of the Showtime
- Labels: `tier:1`, `area:perf`, `needs-benchmark`. Severity high. Status: confirmed.
- Evidence: `JpaBookingRepository.insert` (L76–83) calls `findOccupiedSeats` for the whole Showtime after losing `uq_booked_seat_showtime_seat`, to name 1–8 Seats. Contention Run 1: 14,360 calls for 14,401 rollbacks; 51–149 s of database time per 60 s step.
- Direction: read only the requested Seats, through `booked_seat (showtime_id, seat_id)`.
- Measure: contention, race. ADRs: 0004 (kept).

### P4: book breaks at 50 it/s with nothing saturated
- Labels: `tier:1`, `area:perf`, `needs-benchmark`. Severity high. Status: cause unknown.
- Evidence: breaks only on 1–3 dropped iterations; p95 under 10 ms, 0% errors, Java under 40%, PostgreSQL under 25%. iowait 6–12% against ≤1.8% elsewhere; IO is PostgreSQL's largest wait. book is the only scenario that commits on every request.
- Direction: investigate first (p99/max, WAL sync time, k6 VU allocation), then fix. Group commit is a candidate if commit latency is confirmed.
- Measure: book.

### P3: The Auditorium's seat layout is read on every request
- Labels: `tier:1`, `area:perf`, `needs-benchmark`. Severity medium. Status: confirmed.
- Evidence: `select … from seat where auditorium_id=$1` is browse's top query (9.4–9.7 s per ~25,000 calls) and book's too; `ShowtimeService.pricedSeats` (L59) loads it through `AuditoriumService.get`. Auditoriums are create/read/delete only, so a layout never changes.
- Direction: per-Auditorium cache in the business layer, dropped when the Auditorium is deleted.
- Measure: browse, book.

## Tier 2: the race condition

### A1: A Showtime's Seat inventory is spread over ~10 modules
- Labels: `tier:2`, `area:architecture`, `needs-adr`. Severity high. Status: confirmed.
- Evidence: the showtime adapter writes booking rows and the booking adapter locks the showtime row (`JpaShowtimeRepository.cancel`, `JpaBookingRepository.insert`); `JpaSeatOccupancyReader` lives in `persistence/booking`; `InMemoryBookings` shares `bookingRows` with `InMemoryShowtimes`.
- Direction: one Seat inventory module with two adapters (PostgreSQL, in-memory); behaviour unchanged; folds in A2 and A3. Prerequisite for C1.
- ADRs: 0001, 0004, 0005 kept; new ADR records the module and the one-use-case-one-transaction rule.

### C1: Seat contention has no strategy to compare against the database
- Labels: `tier:2`, `area:concurrency`, `needs-benchmark`, `needs-adr`. Severity high. Status: confirmed.
- Evidence: ADR 0004 leaves pessimistic, optimistic and queue-based booking for phase 2; `src/main` has no concurrent code of its own.
- Direction: behind A1's interface, compare: baseline; showtime row lock (worst case); conditional `UPDATE … RETURNING` on per-Showtime Seat rows; single writer per Showtime with an in-memory seat-map read model. The unique constraint stays under all of them.
- Measure: contention, race (and book). ADRs: 0004 amended or partly superseded.

### C3: Deadlock retry is tested only with Mockito
- Labels: `tier:2`, `area:concurrency`. Severity low. Status: confirmed.
- Evidence: `JpaBookingRepositoryTest` (2 tests, mocks).
- Direction: a Testcontainers test that forces opposite lock orders, run against every C1 strategy.

## Tier 3: protection

### S1: No rate limiting
- Labels: `tier:3`, `area:security`, `needs-benchmark`. Severity high. Status: confirmed (README).
- Direction: Bucket4j token buckets per IP and per account, strictest on login, `429` with `Retry-After`, progressive delay rather than lockout. Lives in the API layer (ADR 0001).
- Measure: account (limiter overhead).

### C2: Seat hoarding through many accounts
- Labels: `tier:3`, `area:concurrency`. Severity high. Status: confirmed (README, ADR 0004).
- Direction: cap Holds per account and IP, cooldown after repeated abandons, email verification. Abandon history is deleted today (ADR 0004), so it needs a counter or event table.

### P5: account queues in the app while the database idles
- Labels: `tier:3`, `area:perf`, `needs-benchmark`. Severity medium. Status: hypothesis.
- Evidence: at 1,600 it/s Tomcat had up to 200 busy threads and 187 requests waited for Hikari, with PostgreSQL at ~0.1 active backends. Pool and threads are defaults; every account read opens a `BEGIN READ ONLY` transaction.
- Direction: tune Hikari and Tomcat or try virtual threads; drop the transaction for single reads. After C1, which changes pool demand.
- Measure: account.

### S3: Payment is simulated and not idempotent
- Labels: `tier:3`, `area:security`. Severity high. Status: confirmed (README).
- Direction: `Idempotency-Key` with a unique constraint, explicit payment state machine, late payment after Hold expiry refunds. A real gateway is out of scope (`tier:out`).

### S2: Tokens cannot be revoked; no logout
- Labels: `tier:3`, `area:security`. Severity high. Status: confirmed (README).
- Direction: short-lived access token plus revocable, rotating refresh token.

### B5: A malformed multipart Content-Type makes any endpoint return 500
- Labels: `tier:1`, `area:bug`. Severity medium. Status: confirmed (Schemathesis ST102, 18 operations).
- Evidence: `curl -X DELETE -H 'Content-Type: multipart/form-data' .../api/auditoriums/<id>` (no boundary) returns 500
  with Spring's default error body. No endpoint accepts uploads, yet the multipart resolver parses every request.
- Direction: `spring.servlet.multipart.enabled: false`, or map `MultipartException` to 400; API test with the curl above.

### B6: Errors handled outside MVC are not problem+json
- Labels: `tier:3`, `area:bug`. Severity low. Status: confirmed (Schemathesis ST105).
- Evidence: a malformed path (`/api/showtimes/%C3%B4d...`) or an empty query parameter name (`?=null`) returns 400 as
  `application/json` `{timestamp,status,error,path}`, while `docs/openapi.json` documents problem+json for every error.
- Direction: make the `/error` fallback render a ProblemDetail.

## Tier 4: if time allows

### P7: Lists are not paginated
- Labels: `tier:4`, `area:perf`. `GET /movies`, `/cinemas`, `/bookings` return every row. Keyset pagination, capped page size, matching index.

### B2: A Refunded Booking whose Hold has not lapsed counts as held
- Labels: `tier:4`, `area:bug`. `findOccupiedSeats` matches `status='CONFIRMED' or expires_at > now`; harmless today only because Cancelled Showtimes are refused first. Untested. A1 can fix it.

### B3: Any integrity error on saving an Account is reported as email taken
- Labels: `tier:4`, `area:bug`. `JpaAccountRepository.save` (L39–42) ignores the constraint name; `InMemoryAccounts` does not enforce unique email.

### B4: The fake and PostgreSQL report errors in different orders
- Labels: `tier:4`, `area:bug`. Status: hypothesis. `InMemoryBookings.saveNew` (L55–64) reports Seats before the one-Pending rule; PostgreSQL likely the reverse.

### S4: One Admin from environment variables, no audit log
- Labels: `tier:4`, `area:security`. Admins in the database, append-only audit table written in the same transaction, through a business port (ADR 0001).

### A4: Dependency cycles around ShowtimeService
- Labels: `tier:4`, `area:architecture`. business.movie ↔ business.showtime, business.auditorium ↔ business.showtime. Mostly gone after A1; add an ArchUnit cycle rule.

### B7: JSON scalars are coerced into string fields
- Labels: `tier:4`, `area:bug`. Status: confirmed (Schemathesis ST201). `{"address": true}` creates a Cinema whose address
  is the string "true". Disable scalar coercion in Jackson for request bodies.

### D1: Rules the API enforces but docs/openapi.json does not state
- Labels: `tier:4`, `area:bug`. Status: confirmed (Schemathesis ST202, ST204).
- Cancellation reason OTHER needs a note; registration passwords have a maximum length; `QUERY` and other undeclared
  methods return 400 instead of 405; en dashes in descriptions are double-encoded and render as mojibake.

### O3: Every lost seat race logs three WARN lines
- Labels: `tier:4`, `area:ops`. Status: confirmed. 62,751 lines from `org.hibernate.orm.jdbc.error` in one 90 s contention
  run; silencing them moved p95 by 4 ms, so this is log noise, not a performance fix.

### DEP1: Known vulnerabilities in dependencies and the base image
- Labels: `tier:3`, `area:security`. Status: confirmed (Trivy fs + image).
- Tomcat 11.0.24 (4 CRITICAL, fixed in 11.0.25), Jackson 3.1.5 and 2.21.5 (HIGH), Alpine openssl and zlib (MEDIUM).
  Gitleaks found no secrets in 227 commits.

### DEP2: Move the Spring Boot parent to 4.1.2 and drop DEP1's overrides
- Labels: `tier:4`, `area:security`, `needs-benchmark`. Status: proposed.
- 4.1.2 manages Tomcat 11.0.27, Jackson 3.1.7 and 2.21.7, so DEP1's three overrides become unnecessary. It also moves
  about 50 managed versions, among them Spring Framework 7.0.10, Spring Security 7.1.2 and Hibernate 7.4.5 → 7.4.13,
  which touches the booking and lock paths: full verify and a contention/race comparison first.
- Also: pin the runtime base image by digest (with Renovate or Dependabot) instead of the floating tag.

## Out of scope on Kaggle

### O2: One instance, no failover, image built but never deployed
- Labels: `tier:out`, `area:ops`. Kaggle gives one 4-vCPU machine. Document as future work.
