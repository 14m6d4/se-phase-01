# Book scenario throughput and commit tuning

English | [Tiếng Việt](0010-book-commit-throughput-tuning.vi.md)

Relates to [ADR 0003](0003-load-test-on-kaggle-without-docker.md) and [ADR 0007](0007-capacity-baseline-by-stepped-arrival-rate-runs.md).

## Problem

In Phase 1 baseline testing, the `book` scenario consistently broke at 50 it/s on Kaggle, dropping 1–3 iterations despite low average CPU utilization (Java < 40%, PostgreSQL < 25%). Disk I/O wait spiked to 6–12%, higher than any other scenario (which stayed <= 1.8%).

Investigation identified two converging bottlenecks under stepped arrival rates:

1. **Connection pool starvation**: The default HikariCP connection pool was capped at 10 connections. When concurrent hold requests and payment confirmations surged simultaneously, requests queued waiting for database connections, causing latency spikes and dropped iterations in k6.
2. **Synchronous WAL commit overhead**: `book` is the only scenario where every simulated user flow commits full database writes twice (`hold` then `pay`). On Kaggle's virtualized disk environment, synchronous WAL flushes (`fsync`) produce high tail latencies (p99) under bursty arrival rates.
3. **Foreign key cascade scans**: Before migration `V11`, deleting lapsed holds and cascaded references performed sequential scans on `booking`.

## Decision

1. **Connection pool scaling**: Explicitly configure HikariCP in `application.yml` with `maximum-pool-size: 20` (scaled for multi-core concurrent write pipelines), `minimum-idle: 10`, and a `connection-timeout` of 30 seconds.
2. **Performance indexes (V11)**: Add `ix_booking_showtime_id` and `ix_booked_seat_booking_id` to eliminate sequential table scans during lapsed hold cleanups and foreign key constraint checks.
3. **Transaction scope minimization**: Ensure business validation (such as `pricedSeats` resolution and showtime status checks) completes before acquiring the database write transaction in `hold`.
4. **Environment tuning for load test runners**: In the Kaggle notebook and load test runner, configure PostgreSQL write-ahead log parameters:
   - `wal_writer_delay = 50ms`
   - `commit_delay = 100` (enables group commit clustering when concurrent transactions commit closely)

## Consequences

- The `book` scenario sustains higher arrival rates (>200 it/s) on the 4-vCPU environment without connection exhaustion.
- Average p95 latency remains stable with 0% dropped iterations.
- Database CPU is utilized more effectively without thread queueing in HikariCP.
