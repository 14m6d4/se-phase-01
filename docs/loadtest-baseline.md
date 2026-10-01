# Kaggle load-test baseline

**Status: accepted.** This is a real Kaggle CPU `SCENARIO="all"` run. All k6 thresholds passed and contention SQL verification confirmed the booking-row count with no duplicate seats.

## Run record

- Run: 2026-09-30 10:57:30 UTC; [Kaggle kernel v2](https://www.kaggle.com/code/tunichdi/se-phase-01-issue19-baseline), using private source dataset v3.
- Configuration: `RATE=50` iterations/s, `HOLD=60s`, 30s ramp-up and 10s ramp-down; k6 v2.3.0.
- Source: full snapshot based on `b763b4b71aa7e0b27aa62b23728e963ca68d89a2`; runtime source ZIP byte SHA-256 `8dfac57ac7c17ad10d873add9bab0c653fd183489a1510fd66c7f825a92dc57f`.
- Environment: Kaggle CPU, 4 vCPU Intel Xeon @ 2.20 GHz, 31 GiB RAM; OpenJDK 21.0.12.1; PostgreSQL 14.24; Hikari maximum pool 10.
- The service and k6 share the notebook CPUs; results are host-dependent. The JAR build succeeded with Maven tests skipped (`-Dmaven.test.skip=true`); this is not a full Maven test result.

| Scenario | Measured requests | Iterations | RPS | p50 (ms) | p95 (ms) | p99 (ms) | Error rate | Dropped iterations | k6 thresholds |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| browse | 11,997 | 3,999 | 119.80 | 2.86 | 6.85 | 11.10 | 0.00% | 0 | PASS |
| book | 7,998 | 3,999 | 78.22 | 8.49 | 12.58 | 15.27 | 0.00% | 0 | PASS |
| contention | 3,999 | 3,999 | 36.85 | 6.51 | 8.25 | 9.47 | 0.00% | 0 | PASS |

Error rate is `http_req_failed{phase:load}.values.rate`; zero is good. k6's `Rate` metric counts false samples in `fails`, so `fails` equaling the request count does **not** mean those HTTP requests failed. The `book` scenario had zero failed measured requests and zero 409 responses.

## Contention verification

- 10 bookings returned HTTP 201; 3,989 expected HTTP 409 conflicts: 3,244 `Seat already taken` and 745 `Customer already has a Pending Booking`. These are separate conflict causes; the latter is the per-Customer pending-booking rule.
- SQL verifier: **PASS**; the 10 bookings matched 10 `booked_seat` rows and duplicate-seat rows were 0.
- The earlier kernel v1 attempt failed SQL verification because its query used PostgreSQL's default `postgres` database rather than application database `cinema`; its printed zero counts were fallback placeholders, not database measurements. Kernel v2 explicitly queried `cinema`, and the SQL assertions passed.

## Evidence

Downloaded with `kaggle kernels output tunichdi/se-phase-01-issue19-baseline -p /tmp/opencode/issue19-kaggle/rerun-output`:

- `/tmp/opencode/issue19-kaggle/rerun-output/results/{browse,book,contention}/summary.json`
- `/tmp/opencode/issue19-kaggle/rerun-output/results/contention-verification.txt`
- `/tmp/opencode/issue19-kaggle/rerun-output/results/env.txt`
- `/tmp/opencode/issue19-kaggle/rerun-output/results/app.log`
- `/tmp/opencode/issue19-kaggle/rerun-output/se-phase-01-issue19-baseline.log`
