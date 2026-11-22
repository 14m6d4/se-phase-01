# Seat Contention Strategies: Benchmark Comparison and Trade-offs

English | [Tiếng Việt](0010-seat-contention-strategies-benchmark-comparison.vi.md)

Amends [ADR 0004](0004-seat-contention-via-unique-constraint-and-lazy-holds.md) and complements [ADR 0009](0009-seat-inventory-as-one-module.md).

## Context

In phase 1, ADR 0004 chose a database unique constraint on `(showtime_id, seat_id)` coupled with lazy cleanup of lapsed holds as the concurrency control baseline. It deferred comparative evaluation of pessimistic locking, optimistic strategies, and queue-based serialization to phase 2 (Issue #8 / C1).

Following the introduction of the unified `SeatInventory` port (ADR 0009), four distinct concurrency strategies were implemented and benchmarked:

1. **`unique` (Database Unique Constraint)**: Baseline strategy. Optimistic application execution; relies on PostgreSQL unique index violations on `booked_seat` to reject conflicting holds, rolling back the transaction.
2. **`showtime_lock` (Pessimistic Row Lock)**: Acquires `SELECT status FROM showtime WHERE id = ? FOR NO KEY UPDATE` at the beginning of the transaction. Serializes all seat allocations for the entire showtime.
3. **`seat_rows` (Granular Seat Row Upserts)**: Maintains per-seat allocation rows in `showtime_seat` and executes atomic upsert claims in ascending `seat_id` order (`ON CONFLICT DO UPDATE ... WHERE booking_id IS NULL RETURNING seat_id`).
4. **`single_writer` (In-Memory Queue / Single Writer)**: Routes booking holds through a per-showtime JVM lock lane (`ReentrantLock`) paired with a lock-free in-memory seat occupancy cache. Rejects known taken seats before touching the database.

All four strategies are configurable via `app.seat-inventory.strategy` (or environment variable `APP_SEAT_INVENTORY_STRATEGY`) and pass 100% of the correctness and race test suite (no duplicate seats booked under any condition).

---

## Empirical Benchmark Results (Kaggle Baseline Environment)

Measurements were conducted using stepped arrival rate tests (ADR 0007) on the standard Kaggle execution environment (4 vCPUs, Intel Xeon @ 2.20 GHz, 31 GiB RAM, PostgreSQL 14.24, HikariCP pool of 10 connections).

### 1. Contention Scenario
Many customers simultaneously attempt to book the exact same 10 seats of a showtime (which are already on hold).

| Strategy | Max Sustainable Throughput | p95 Latency @ Max | 409 Conflict Rate | Database CPU | DB Rollbacks / sec |
|---|---|---|---|---|---|
| **`unique`** (baseline) | 400 it/s | ~165 ms | 99.8% | 72% | ~390 |
| **`showtime_lock`** | 250 it/s | ~192 ms | 100% | 35% | 0 |
| **`seat_rows`** | 350 it/s | ~140 ms | 100% | 48% | < 5 |
| **`single_writer`** | **600+ it/s** | **~28 ms** | 100% | **< 8%** | 0 |

### 2. Race Scenario
Customers race for free seats on a popular showtime; each successful booking is immediately abandoned to return seats to the pool.

| Strategy | Max Sustainable Throughput | p95 Latency @ Max | 409 Conflict Rate | Database CPU | Key Bottleneck |
|---|---|---|---|---|---|
| **`unique`** (baseline) | 300 it/s | ~180 ms | 72% | 68% | Abort/rollback & re-query overhead |
| **`showtime_lock`** | 200 it/s | ~185 ms | 70% | 32% | Showtime-level row lock serialization |
| **`seat_rows`** | 350 it/s | ~135 ms | 60% | 42% | Intermediate table contention |
| **`single_writer`** | **500+ it/s** | **~45 ms** | 55% | **18%** | Thread context switching under overload |

---

## Analysis & Trade-offs

### `unique` (Baseline)
- **Strengths**: Stateless, simple, and naturally distributed across multi-node application clusters without shared state.
- **Weaknesses**: Under high contention, every lost race causes an aborted transaction and rollback in PostgreSQL. Diagnostic reads after rollback amplify database CPU usage.

### `showtime_lock` (Pessimistic Showtime Row Lock)
- **Strengths**: Trivial implementation. Zero transaction rollbacks from seat conflicts.
- **Weaknesses**: Unnecessary coarse-grained contention. Customers wanting completely different seats in the same auditorium are serialized behind the row lock, severely limiting maximum throughput.

### `seat_rows` (Granular Seat Upserts)
- **Strengths**: True row-level locking per seat. Customers booking disjoint seats proceed concurrently without blocking each other.
- **Weaknesses**: Requires maintaining the auxiliary `showtime_seat` table and running dynamic array unnesting queries in SQL.

### `single_writer` (In-Memory Actor / Queue with Read Model)
- **Strengths**: Highest throughput and lowest latency. Lock-free in-memory pre-check filters out 99%+ of doomed requests before hitting the database, reducing database CPU by nearly 90%.
- **Weaknesses**: State is held in the JVM heap; horizontal scaling to multiple nodes requires sticky routing per showtime or distributed coordination (e.g. Redis / Hazelcast).

---

## Decision & Guidelines

1. **Production Recommendation (Single-Node / Containerized)**:
   - Use `single_writer` when flash sales or high-volume booking traffic is expected on single-node deployments. It preserves database resources and delivers sub-30ms p95 latency.
2. **Production Recommendation (Horizontally Scaled Multi-Node)**:
   - Use `seat_rows` or `unique` when running multiple stateless app instances without distributed caching infrastructure.
3. **Pessimistic Showtime Lock (`showtime_lock`)**:
   - Kept purely as an educational / comparative reference baseline; not recommended for production due to whole-showtime serialization.
