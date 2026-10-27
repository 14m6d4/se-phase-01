# Kaggle load-test baseline

The 2026-10-22 run completed all four scenarios with 100% passing checks, no unexpected HTTP failures and no dropped iterations. SQL confirmed 10 bookings for 10 distinct Seats.

Run instructions: [load-test guide](../loadtest/README.md).

## Configuration

| Setting | Value |
|---|---|
| Code commit | `6d07b393e5b0b482cb0e832b54befeda0652946f` |
| Report timestamp | 2026-10-22 04:09:58 UTC |
| Scenarios | smoke, browse, book, contention, in order |
| Target rate | 50 iterations/s |
| Duration per scenario | 30s ramp-up, 60s hold, 10s ramp-down |
| CPU | 4 vCPUs, Intel Xeon @ 2.20 GHz |
| RAM | 31 GiB |
| Java | OpenJDK 21.0.12, G1 GC, no JVM flags |
| PostgreSQL | 16.15; max connections: 100 |
| Hikari pool | Maximum 10 connections |
| k6 | v2.3.0 |

The service, PostgreSQL and k6 shared the Kaggle CPU notebook. The run tested the working-tree changes later committed as `6d07b39`, on top of `54b5b06`. Only documentation differs from that code commit. Raw outputs are not committed.

## Results

| Scenario | Measured requests | Iterations | Run-average RPS | p50 (ms) | p95 (ms) | p99 (ms) | Unexpected HTTP errors | Dropped iterations |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| smoke | 3,999 | 3,999 | 36.58 | 3.03 | 5.40 | 7.01 | 0.00% | 0 |
| browse | 7,998 | 3,999 | 79.90 | 3.51 | 5.75 | 9.09 | 0.00% | 0 |
| book | 7,998 | 3,999 | 78.34 | 7.98 | 11.47 | 15.75 | 0.00% | 0 |
| contention | 3,999 | 3,999 | 37.00 | 9.09 | 11.54 | 12.99 | 0.00% | 0 |

All scenarios exited 0 and passed the enforced thresholds: all response checks passed, no measured HTTP request failed unexpectedly and no iterations were dropped. The latency limits printed by `report.py` remain advisory.

RPS includes setup and both ramps in elapsed time. Browse and book each make two measured requests per iteration; smoke and contention make one. Latency is per HTTP request, so book's p95 of 11.47 ms does not measure the combined hold-and-pay workflow. These results cover the configured load, not maximum capacity.

## Contention

Of 3,999 booking attempts:

- 10 returned HTTP 201.
- 3,244 returned a seat conflict.
- 745 returned a conflict because the Customer already had a Pending Booking.

SQL found 10 booked-seat rows for 10 distinct Seats, matching the successful responses. Expected HTTP 409 responses passed the body checks and did not count as HTTP failures.

The verifier requires at least one booking and one seat conflict, not exactly 10 successes. This scenario does not synchronise requests into a simultaneous race.
