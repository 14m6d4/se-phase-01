# Kaggle load-test baseline

**Status: accepted.**

**Scope:** this run used a working-tree snapshot based on `01a71f748a18a4bd552cb44429c1b41820d8e79d`, not a clean checkout of that commit, and the notebook's native `CODE_BUNDLE="auto"` selected the attached source snapshot.

## Run record

- Run: 2026-10-21 01:37:01 UTC; [Kaggle kernel version 5](https://www.kaggle.com/code/tunichdi/se-phase-01-issue19-baseline/versions/5), using private source [dataset version 5](https://www.kaggle.com/datasets/tunichdi/se-phase-01-issue19-source/versions/5). These private artifacts require access. Reproducing this run requires the versioned source snapshot below, not a checkout of its base Git commit.
- Configuration: `SCENARIO=all`, `RATE=50` iterations/s, `DURATION=60s`, 30s ramp-up and 10s ramp-down; k6 v2.3.0.
- Source: **working-tree snapshot based on `01a71f748a18a4bd552cb44429c1b41820d8e79d`, including the Issue #46 fixes that were uncommitted at run time; not a clean checkout of that commit.** Manifest: `{"snapshotMode":"working-tree","sourceCommit":"01a71f748a18a4bd552cb44429c1b41820d8e79d"}`.
- Source artifact: ZIP byte SHA-256 `32db0cd0fea7a368eba20d3d53e5275cf2bbe1190c4cdf525dc119e278896ace`; full source-content fingerprint `a4ea57b28497febfd3126a6cd44cadcde22f98db8d03f405c56cc32cf401fcc1` over all 161 files (160 source files plus the manifest). The fingerprint hashes sorted relative names as UTF-8 + NUL, followed by each file's 8-byte big-endian length and content, ignoring executable flags.
- The staged Parameters cell used `CODE_BUNDLE="auto"` to select the single attached source root below `/kaggle/input`; the notebook build cell (`c08`) then used its normal strict loader. The repository notebook still defaults to `CODE_BUNDLE = None`.
- Build-cell expanded-tree hashes: `43c4012905d87e5e37dc4a6d9664189aacf73b0c88df654b16d8a392e7ea6ca9` as actually mounted; `ead55032698317236dcc133d4bc733966401c2dce13515abb1d3ce61b22bd37f` using ZIP entry modes. The mount lost the `mvnw` executable bit, so only the actual value is in `results/env.txt`; the build cell restores that bit after hashing. Tree hashes cover sorted relative paths, executable flags, lengths and bytes, and are not ZIP archive hashes.
- Runtime environment (CPU, OS packages, Java, PostgreSQL, pool) is recorded in `results/env.txt`; it is not copied into this document. The Kaggle JAR build used `-Dmaven.test.skip=true`, so this is not a full Maven test result. A separate local `./mvnw verify` run passed 127 tests with 0 failures/errors/skips.

| Scenario | Measured requests | Iterations | RPS | p50 (ms) | p95 (ms) | p99 (ms) | Error rate | Dropped iterations | k6 thresholds |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| browse | 7,998 | 3,999 | 79.86 | 3.68 | 7.27 | 12.61 | 0.00% | 0 | PASS |
| book | 7,998 | 3,999 | 78.23 | 7.88 | 11.31 | 14.85 | 0.00% | 0 | PASS |
| contention | 3,999 | 3,999 | 37.06 | 7.83 | 9.35 | 10.84 | 0.00% | 0 | PASS |

Measurements are from the actual Kaggle summaries, not local runs. Error rate is `http_req_failed{phase:load}.values.rate`; zero is good. k6's `Rate` metric counts false samples in `fails`, so `fails` equaling the request count does **not** mean those HTTP requests failed. Browse made exactly 2 measured requests per iteration; book also made 2; contention made 1. Every selected scenario ran 3,999 iterations.

## Contention verification

- 10 bookings returned HTTP 201; 3,989 expected HTTP 409 conflicts: 3,244 seat conflicts and 745 pending-booking conflicts. These are separate causes; the latter is the per-Customer pending-booking rule.
- SQL verifier: **PASS**; the 10 bookings matched 10 `booked_seat` rows and duplicate-seat rows were 0.

## Thresholds

Each selected scenario exited 0 and saved its threshold results in `summary.json` under `metrics.<metric>.thresholds`; every recorded threshold has `ok: true`. Browse records only the phase-filtered generic thresholds `http_reqs{phase:load} count>=0`, `http_req_failed{phase:load} rate>=0`, and `http_req_duration{phase:load} max>=0`; these are not latency SLAs. Book enforces `checks rate==1` and `http_req_failed{phase:load} rate==0`. Contention enforces the same checks and zero-failure requirement, plus non-zero `bookings_created`, `booking_conflicts`, and `seat_conflicts`. The `report.py` lines such as `p95 < 500 ms` are advisory output, not k6 thresholds.

## Evidence

Artifacts are referenced relative to the kernel version's output:

- `results/browse/summary.json`
- `results/book/summary.json`
- `results/contention/summary.json`
- `results/contention/contention.json`
- `results/contention-verification.txt`
- `results/env.txt`
- `results/app.log`

The recorded outputs were downloaded immediately after this run with the Kaggle CLI 2.2.4 base kernel reference. `kernels output` selects only the latest version: once another version exists, use the pinned version page's Output tab rather than treating latest output as this run. The versioned dataset and kernel pages above are the durable private references.

## Historical note

Kernel version 4 with dataset version 4 (2026-10-20) was the previous accepted baseline. It used a custom staged source locator rather than native `auto`; its measurements are not mixed into this table. Kernel v2 (2026-09-30, source `b763b4b`) is also not comparable: it predates the current `seatIds` contention wire and its browse scenario made 3 requests per iteration. Those old measurements are historical only.
