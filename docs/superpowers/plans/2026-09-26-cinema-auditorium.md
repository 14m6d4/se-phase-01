# Cinema and Auditorium Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship issue #2: admin Cinema/Auditorium writes, public catalogue reads and immutable generated Seat layouts.

**Architecture:** Mirror Movie's business interface → JPA adapter → BusinessConfig → controller slice. Create three FK-linked tables in V3; generate Seats once in framework-free business; adapter saves Auditorium and Seats atomically. Leave Showtime-dependent deletion to #3.

**Tech Stack:** Java 21, Spring Boot 4, PostgreSQL/Flyway, Spring Data JPA, Bean Validation, MockMvc, Testcontainers, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-26-cinema-auditorium-design.md`; `docs/spec.md`, issue #2.

## Global Constraints

- `business` depends only on `java.*` and `business` (ADR 0001); no Spring/JPA annotations.
- `config/SecurityConfig` alone declares authorization; public GETs/admin writes already configured. Annotate each write with `@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)`.
- Persistence naming: `CinemaEntityRepository`/`JpaCinemaRepository`, `AuditoriumEntityRepository`/`JpaAuditoriumRepository`; business exceptions extend `ConflictException`/`NotFoundException` except invalid layout, which must map to problem+json 400.
- Schema is Flyway V3, `ddl-auto: validate`, UUID generated in business. Docker is required for Testcontainers. Do not stage unrelated `.agents/` or `skills-lock.json`.
- Commit each tested increment with Conventional Commits and push branch `feat/2-cinema-auditorium`. Finish with `./mvnw verify`; create PR into `main` with `Closes #2`; never merge.

## Review Focus

1. Concurrent inserts of the same Auditorium name: DB unique constraint must yield 409; Task 3 tests duplicate sequentially and tests adapter's constraint backstop.
2. Cinema deletion with Auditorium: 409, never silently cascade child deletion; Task 3 integration test.
3. Seat list with overlapping, duplicate or out-of-range row letters: 400 and no partial layout; Task 2 unit/API tests.
4. Unauthenticated or Customer catalogue writes: 401/403 and public GET: 200; Task 3 integration test.
5. Existing `AuthenticationApiTest` assumes admin Cinema POST returns 404: Task 3 replaces assertion with valid 201 and removes obsolete comment.

---

### Task 1: Cinema CRUD and schema

**Files:**
- Create: `src/main/java/vn/edu/uet/cinema/business/cinema/Cinema.java`, `CinemaRepository.java`, `CinemaService.java`
- Create: `src/main/java/vn/edu/uet/cinema/persistence/cinema/CinemaEntity.java`, `CinemaEntityRepository.java`, `JpaCinemaRepository.java`
- Create: `src/main/java/vn/edu/uet/cinema/api/cinema/CinemaController.java`
- Create: `src/main/resources/db/migration/V3__create_cinema_auditorium_seat.sql` (all three tables and constraints at once; Flyway migrations must never be edited after publication)
- Modify: `src/main/java/vn/edu/uet/cinema/config/BusinessConfig.java`
- Test: `src/test/java/vn/edu/uet/cinema/api/cinema/CinemaApiTest.java`

**Interfaces:**
- Produces `Cinema(UUID id, String name, String address)`; `CinemaRepository { List<Cinema> findAll(); Optional<Cinema> findById(UUID id); Cinema save(Cinema cinema); void deleteById(UUID id); }`.
- Produces `CinemaService(CinemaRepository)` with `create(String name,String address)`, `list()`, `get(UUID id)`, `update(UUID id,String name,String address)`, `delete(UUID id)`. Task 3 adds `AuditoriumRepository` to the constructor for dependent-delete check.

- [ ] **Step 1: Write failing API test** `CinemaApiTest.adminCanCreateReadUpdateAndDeleteCinema`: login using existing Movie test's admin credentials; POST 201 and response `id/name/address`; GET list and id, PUT new name/address, DELETE 204, GET deleted id 404. Also test unknown PUT/DELETE 404.
- [ ] **Step 2: Run red check** `./mvnw test -Dtest=CinemaApiTest` → FAIL (endpoints absent).
- [ ] **Step 3: Implement exact interfaces** above. Follow Movie model/entity/controller; nested `CinemaRequest(@NotBlank @Size(max=255) String name, @NotBlank String address)` and `CinemaResponse`. V3 includes `cinema(id uuid primary key,name varchar(255) not null,address text not null)`, plus the `auditorium` and `seat` tables/constraints specified in Task 3 Step 3; write them now and do not later alter V3. Wire `CinemaService` in `BusinessConfig`.
- [ ] **Step 4: Run green check** `./mvnw test -Dtest=CinemaApiTest` → PASS; `git diff --check` → clean.
- [ ] **Step 5: Commit and push** `git add` only Task 1 files; `git commit -m "feat(cinema): add cinema catalogue CRUD"`; `git push`.

### Task 2: Generated Seat layout

**Files:**
- Create: `src/main/java/vn/edu/uet/cinema/business/auditorium/SeatType.java`, `Seat.java`, `SeatLayout.java`, `InvalidSeatLayoutException.java`
- Modify: `src/main/java/vn/edu/uet/cinema/api/ApiExceptionHandler.java` (400 mapping)
- Test: `src/test/java/vn/edu/uet/cinema/business/auditorium/SeatLayoutTest.java`

**Interfaces:**
- Produces enum `SeatType { STANDARD, VIP, COUPLE }`, record `Seat(UUID id,String row,int number,SeatType type)` with `label()` returning `row + number`.
- Produces `SeatLayout.generate(int rows,int seatsPerRow,List<String> vipRows,List<String> coupleRows): List<Seat>`; throws `InvalidSeatLayoutException` for rows outside 1–26, seatsPerRow < 1, null row lists, null/duplicate/out-of-range row values, or overlap. Returned Seat list is immutable and row-major. Generate UUIDs here once per Seat; `COUPLE` yields one Seat per row/number.

- [ ] **Step 1: Write failing pure unit tests** `SeatLayoutTest`: 3 × 2 with VIP B and COUPLE C yields labels `[A1,A2,B1,B2,C1,C2]` and types `[STANDARD,STANDARD,VIP,VIP,COUPLE,COUPLE]`; 1 × 1 COUPLE yields exactly one Seat; distinct UUIDs; reject zero/27 rows, zero seats, duplicate/overlapping/out-of-range row lists and null row element with `InvalidSeatLayoutException`.
- [ ] **Step 2: Run red check** `./mvnw test -Dtest=SeatLayoutTest` → compilation/test FAIL before implementation.
- [ ] **Step 3: Implement exact interfaces** and make `ApiExceptionHandler` map `InvalidSeatLayoutException` to `ProblemDetail` 400. Keep validation in JDK business as well as HTTP boundary.
- [ ] **Step 4: Run green check** `./mvnw test -Dtest=SeatLayoutTest` → PASS; `git diff --check` → clean.
- [ ] **Step 5: Commit and push** `git add` only Task 2 files; `git commit -m "feat(auditorium): generate immutable seat layout"`; `git push`.

### Task 3: Auditorium persistence, endpoints and deletion guards

**Files:**
- Create: `src/main/java/vn/edu/uet/cinema/business/auditorium/Auditorium.java`, `AuditoriumRepository.java`, `AuditoriumService.java`, `AuditoriumNameTakenException.java`
- Create: `src/main/java/vn/edu/uet/cinema/persistence/auditorium/AuditoriumEntity.java`, `SeatEntity.java`, `AuditoriumEntityRepository.java`, `JpaAuditoriumRepository.java`
- Create: `src/main/java/vn/edu/uet/cinema/api/auditorium/AuditoriumController.java`
- Modify: `src/main/java/vn/edu/uet/cinema/business/cinema/CinemaService.java`, `src/main/java/vn/edu/uet/cinema/persistence/cinema/JpaCinemaRepository.java`, `src/main/java/vn/edu/uet/cinema/config/BusinessConfig.java`, `src/test/java/vn/edu/uet/cinema/api/account/AuthenticationApiTest.java`
- Test: `src/test/java/vn/edu/uet/cinema/api/auditorium/AuditoriumApiTest.java`, plus `CinemaApiTest.java`.

**Interfaces:**
- Consumes `CinemaService.get(UUID)`, `SeatLayout.generate(...)`, Seat model/type, `InvalidSeatLayoutException`, Cinema repository.
- Produces `Auditorium(UUID id,UUID cinemaId,String name,int rows,int seatsPerRow,List<Seat> seats)`; `AuditoriumRepository { List<Auditorium> findByCinemaId(UUID cinemaId); Optional<Auditorium> findById(UUID id); boolean existsByCinemaId(UUID cinemaId); boolean existsByCinemaIdAndName(UUID cinemaId,String name); Auditorium save(Auditorium auditorium); void deleteById(UUID id); }`. `AuditoriumService(CinemaService,AuditoriumRepository)` has `create(UUID cinemaId,String name,int rows,int seatsPerRow,List<String> vipRows,List<String> coupleRows)`, `list(UUID cinemaId)`, `get(UUID id)`, `delete(UUID id)`. `CinemaService(CinemaRepository,AuditoriumRepository)` adds `existsByCinemaId` check before delete.
- Translate only unique-name constraint `uq_auditorium_cinema_name` to `AuditoriumNameTakenException extends ConflictException`; other integrity errors must not be mislabeled. DB FK `fk_auditorium_cinema` blocks race with Cinema delete; translate that known constraint to 409 in Cinema adapter, not blanket every persistence error.

- [ ] **Step 1: Write failing integration tests** `AuditoriumApiTest`: admin creates two Cinemas; POST auditorium under one with 3 × 2, VIP B, COUPLE C → 201; public GET parent list and auditorium detail with labels/types, stable Seat IDs on repeat GET; duplicate name in same Cinema → 409, same name in other Cinema → 201; deleting Cinema with Auditorium → 409; delete Auditorium → 204 then Cinema → 204; missing parent/detail/delete → 404; invalid row lists/dimensions → 400 problem+json; anonymous/customer POST/DELETE → 401/403; docs include paths and security on writes, none on GET. Add `CinemaApiTest` explicit 409 with dependent child.
- [ ] **Step 2: Run red check** `./mvnw test -Dtest=AuditoriumApiTest` → FAIL.
- [ ] **Step 3: Implement exact interfaces**. Use the already-published V3 schema (created in Task 1): `auditorium(id uuid primary key, cinema_id uuid not null, constraint fk_auditorium_cinema foreign key(cinema_id) references cinema(id) on delete restrict, name varchar(255) not null, rows integer not null check(rows between 1 and 26), seats_per_row integer not null check(seats_per_row>0), constraint uq_auditorium_cinema_name unique(cinema_id,name))` and `seat(id uuid primary key, auditorium_id uuid not null, row_letter varchar(1) not null, seat_number integer not null check(seat_number>0), type varchar(8) not null check(type in ('STANDARD','VIP','COUPLE')), constraint fk_seat_auditorium foreign key(auditorium_id) references auditorium(id), unique(auditorium_id,row_letter,seat_number))`. JPA adapter `saveAndFlush` Auditorium with cascading Seats in one `@Transactional` method; read seats without lazy-initialization failure; delete Seats with parent. Explicit pre-checks plus named-constraint fallback (inspect nested constraint cause), preserving 404. `AuditoriumRequest(@NotBlank @Size(max=255) String name,@NotNull @Min(1) @Max(26) Integer rows,@NotNull @Positive Integer seatsPerRow,@NotNull List<String> vipRows,@NotNull List<String> coupleRows)`; nested response records. Rewire services; replace obsolete Account test 404 assertion with authenticated valid POST expecting 201.
- [ ] **Step 4: Run green check** `./mvnw test -Dtest=AuditoriumApiTest,CinemaApiTest,AuthenticationApiTest` → PASS; `git diff --check` → clean.
- [ ] **Step 5: Commit and push** `git add` only Task 3 files; `git commit -m "feat(auditorium): persist catalogue and guard dependent deletes"`; `git push`.

### Task 4: Full verification and PR

**Files:** No new product files expected; targeted fixes only for test findings.

- [ ] **Step 1: Run complete verification** `./mvnw verify` with Docker running → BUILD SUCCESS, zero failing tests; if it fails, diagnose and fix root cause, rerun before claiming success.
- [ ] **Step 2: Review changes** ask fresh read-only subagent reviewer to compare branch against issue #2, design, `docs/spec.md`, security, DB integrity and test evidence; fix actionable findings, rerun `./mvnw verify`, commit fixes separately, push.
- [ ] **Step 3: Check publication** `git diff origin/main...HEAD --check`, `git status --short`, ensure untracked `.agents/` and `skills-lock.json` remain unstaged; `gh pr create --base main --head feat/2-cinema-auditorium --title "feat(cinema): add Cinema and Auditorium catalogue" --body "Closes #2"` → PR URL. Do not merge.
