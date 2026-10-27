# Kaggle load-test baseline

The 2026-10-27 run completed all four scenarios with 100% passing checks, no unexpected HTTP failures and no dropped iterations. SQL confirmed 10 bookings for 10 distinct Seats.

Public notebook: [Kaggle notebook (version 6)](https://www.kaggle.com/code/tunichdi/se-phase-01-issue19-baseline/versions/6). Run instructions: [load-test guide](../loadtest/README.md). The notebook's source Dataset is private; only the notebook and its outputs are public.

## Configuration

| Setting | Value |
|---|---|
| Notebook | Kaggle CPU notebook, version 6 |
| Code commit | `3a827a0e0b631abc6dc3f2f958b6ebba7b85b875` |
| Source bundle | ZIP SHA-256 `3ef99329f0c6e66a9f91c2e2ae98dfbb5a5c70589359ee62dd2817eb1834a753`; expanded tree SHA-256 `239e2ef4a35febb57ae7427e52990286e12162f6958c68f9e593e6d91943a3e7` |
| Report timestamp | 2026-10-27 08:58:10 UTC |
| Scenarios | smoke, browse, book, contention, in order |
| Target rate | 50 iterations/s |
| Duration per scenario | 30s ramp-up, 60s hold, 10s ramp-down |
| CPU | 4 vCPUs, Intel Xeon @ 2.20 GHz |
| RAM | 31 GiB |
| Java | OpenJDK 21.0.12.1, G1 GC, no JVM flags |
| PostgreSQL | 14.24; max connections: 100 |
| Hikari pool | Maximum 10 connections |
| k6 | v2.3.0 |

The service, PostgreSQL and k6 shared the Kaggle CPU notebook. The run used a clean source snapshot of `3a827a0`, built with the notebook's native `CODE_BUNDLE="auto"`. Kaggle's extraction does not keep the executable bit on `mvnw`, so the notebook restores it before building. This run replaces the 2026-10-22 baseline. Raw outputs are not committed.

## Results

| Scenario | Measured requests | Iterations | Run-average RPS | p50 (ms) | p95 (ms) | p99 (ms) | Unexpected HTTP errors | Dropped iterations |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| smoke | 3,999 | 3,999 | 36.67 | 2.80 | 5.09 | 6.60 | 0.00% | 0 |
| browse | 7,998 | 3,999 | 79.89 | 3.06 | 5.22 | 8.78 | 0.00% | 0 |
| book | 8,000 | 4,000 | 78.39 | 6.21 | 9.80 | 13.06 | 0.00% | 0 |
| contention | 3,999 | 3,999 | 37.02 | 6.30 | 8.32 | 10.26 | 0.00% | 0 |

All four scenarios exited 0 and passed the enforced thresholds: all response checks passed, no measured HTTP request failed unexpectedly and no iterations were dropped. The latency limits printed by `report.py` remain advisory.

RPS includes setup and both ramps in elapsed time. Browse and book each make two measured requests per iteration; smoke and contention make one. Latency is per HTTP request, so book's p95 of 9.80 ms does not measure the combined hold-and-pay workflow. These results cover the configured load, not maximum capacity.

## Contention

Of 3,999 booking attempts:

- 10 returned HTTP 201.
- 3,244 returned a seat conflict.
- 745 returned a conflict because the Customer already had a Pending Booking.

The 3,989 conflicts are the two conflict types combined. SQL found 10 booked-seat rows for 10 distinct Seats, matching the successful responses. Expected HTTP 409 responses passed the body checks and did not count as HTTP failures.

The verifier requires at least one booking and one seat conflict, not exactly 10 successes. This scenario does not synchronise requests into a simultaneous race.
