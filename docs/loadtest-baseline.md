# Kaggle load-test baseline

Kaggle CPU run, 2026-10-21: 50 iterations/s held for 60s. No unexpected HTTP failures or dropped iterations. Contention created 10 bookings; SQL found 10 booked-seat rows and no duplicates.

Run instructions: [load-test guide](../loadtest/README.md).

## Run configuration

| Setting | Value |
|---|---|
| Run time | 2026-10-21 01:37:01 UTC |
| Scenarios | browse, book, contention, run sequentially |
| Target rate | 50 workflow iterations/s during the hold stage |
| Duration | 30s ramp-up, 60s hold, 10s ramp-down per scenario |
| Load generator | k6 v2.3.0 |
| Platform | Kaggle CPU notebook; service, PostgreSQL and k6 share the machine |

The exact source snapshot and raw outputs for this run are not included in the repository. To take a new measurement, follow the load-test guide and save the generated `env.txt` alongside the results.

## Results

| Scenario | Measured requests | Iterations | Run-average RPS | p50 (ms) | p95 (ms) | p99 (ms) | Unexpected HTTP errors | Dropped iterations |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| browse | 7,998 | 3,999 | 79.86 | 3.68 | 7.27 | 12.61 | 0.00% | 0 |
| book | 7,998 | 3,999 | 78.23 | 7.88 | 11.31 | 14.85 | 0.00% | 0 |
| contention | 3,999 | 3,999 | 37.06 | 7.83 | 9.35 | 10.84 | 0.00% | 0 |

All recorded thresholds passed.

### How to interpret the numbers

- An iteration runs one workflow. Browse lists Showtimes and opens a seat map; book holds a Seat and pays. Each makes two requests, while contention makes one booking attempt.
- RPS is the measured request count divided by elapsed time, including setup and ramps. It differs from the rate during the hold stage.
- p95 means 95% of individual HTTP requests completed within this duration. Book's 11.31 ms applies to each request, not the combined hold-and-pay workflow.
- Error rate counts unexpected HTTP outcomes. Contention expects valid `409` responses, so it can record zero errors even when booking attempts are rejected.

In raw summaries, read `http_req_failed{phase:load}.values.rate`. Its `fails` count records false samples, meaning requests that did not fail.

## Contention outcome

Of 3,999 attempts:

- 10 returned HTTP 201, each creating a one-Seat Pending Booking.
- 3,244 returned a seat conflict because the requested Seat was already taken.
- 745 returned a pending-Customer conflict because that Customer already had a live Pending Booking.

SQL matched 10 successful responses to 10 `booked_seat` rows with no duplicates. The verifier does not require 10 successes or simultaneous attempts on every Seat.

## What PASS meant in this run

This run used the thresholds below, before PR #54. All scenarios exited 0, with `ok: true` in the saved `metrics.<metric>.thresholds` entries.

| Scenario | Enforced conditions in this historical run |
|---|---|
| browse | Generic metric-export thresholds only; they did not reject failed requests or checks |
| book | All response checks passed; zero unexpected measured HTTP failures |
| contention | Same response-check and HTTP-failure rules as book; at least one booking, booking conflict and seat conflict |

Zero drops was measured but not enforced. Current scripts require all checks to pass, zero unexpected HTTP failures and zero drops.

`report.py` latency checks are advisory. Kaggle skipped Maven tests; a separate local `./mvnw verify` recorded 127 tests with no failures, errors or skips.
