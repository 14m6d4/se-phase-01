# Load test

Run k6 against the service and PostgreSQL, locally or in a Kaggle CPU notebook. See [loadtest-baseline.md](../docs/loadtest-baseline.md) for the latest results and the public [Kaggle notebook (version 6)](https://www.kaggle.com/code/tunichdi/se-phase-01-issue19-baseline/versions/6) for the run and outputs.

## Scenarios

An iteration is one workflow. A virtual user (VU) is a k6 worker.

| Scenario | Workflow | Requests/iteration |
|---|---|---:|
| `smoke` | Read the signed-in Customer's account | 1 |
| `browse` | List Showtimes, open a seat map | 2 |
| `book` | Hold one Seat, pay | 2 |
| `contention` | Try to hold one of the same 10 Seats | 1 |

`SCENARIO="all"` runs smoke, browse, book and contention in order. Setup requests, including login, are excluded from measured requests.

## Run locally

Start the service and PostgreSQL using the main README. Install the k6 version pinned by `K6_VERSION` in `kaggle.ipynb`.

**Use a fresh test database.** Run the seed once, after the service is healthy and Flyway has applied the migrations:

```bash
psql -v ON_ERROR_STOP=1 "<your postgres url for the cinema database>" -f loadtest/seed.sql
mkdir -p loadtest/results
k6 run -e RATE=20 -e DURATION=10s loadtest/k6/browse.js
python3 loadtest/report.py
```

To run another scenario, change the script path ending in `browse.js`. Run the helper tests with `python3 loadtest/test_loadtest.py` (Python and Node required).

## Run on Kaggle

The notebook installs PostgreSQL, builds the service as a JAR and runs k6. All three share the notebook CPUs; Docker is unavailable. See [ADR 0003](../docs/adr/0003-load-test-on-kaggle-without-docker.md).

1. Import `loadtest/kaggle.ipynb`. Set **Accelerator: None**, **Internet: On** and complete any required account verification.
2. Choose the source:
   - Git: set `GIT_REF` and leave `CODE_BUNDLE=None`. Attach `GITHUB_TOKEN` in **Add-ons → Secrets**, with **Contents: Read-only** access to `14m6d4/se-phase-01`.
   - Local changes: attach a private source Dataset and set `CODE_BUNDLE="auto"`; see [Source bundle](#source-bundle). No GitHub token needed.
3. Set `SCENARIO`, `RATE` and `DURATION`. Defaults: `all`, 50 iterations/s, `60s`.
4. Select **Save Version → Save & Run All (Commit)**. Read the final report and download files from **Output**.

Re-import after notebook changes, including the `HOLD` → `DURATION` rename. A saved run continues unattended; the interactive editor stops after 20 minutes idle. Startup failures print an explanation or service log tail.

## Read the results

Every scenario ramps up for 30s, holds `RATE` iterations/s for `DURATION`, then ramps down for 10s. The default run schedules about 4,000 iterations per scenario.

| Metric | Meaning |
|---|---|
| RPS | Measured HTTP requests divided by total elapsed time, including setup and ramps |
| p50 / p95 / p99 | Duration within which 50% / 95% / 99% of measured HTTP requests completed |
| Error rate | Unexpected HTTP outcomes; valid contention `409` responses are expected |
| Dropped iterations | Scheduled iterations k6 could not start within the VU limit |

Latency measures individual requests; a booking workflow takes two. RPS averages the requests over this run and does not measure maximum capacity. Check hardware and runtime settings in `env.txt` when comparing runs.

k6 passes when all response checks pass, no measured HTTP request fails unexpectedly and no iterations are dropped. Contention also needs a successful booking and a seat conflict. The notebook runs SQL verification automatically; a local k6 run checks HTTP responses only. The notebook reports threshold failures after all selected scenarios finish. The latency and error limits printed by `report.py` are advisory.

| Output | Contents |
|---|---|
| `<scenario>/summary.json` | Metrics and threshold results; local k6 writes `summary.json` directly unless `RESULTS_DIR` is set |
| `contention/contention.json` | Tested Showtime; local k6 writes `contention.json` directly unless `RESULTS_DIR` is set |
| `contention-verification.txt` | Notebook only: HTTP counts versus SQL rows, duplicate check |
| `env.txt` | Notebook only: source identity, hardware, JVM, PostgreSQL and pool settings |
| `app.log` | Service log saved by the notebook |

Kaggle saves these under `results/`; local scripts default to `loadtest/results/`. `summary.json` at the output root copies the last scenario only. **Keep reports and raw outputs local; do not commit them.**

## Seed and limits

`seed.sql` creates 3 Cinemas, 12 Auditoriums with 120 Seats each, 20 Movies and 420 Showtimes across seven days starting tomorrow in Vietnam time. It also creates 2,000 Customers: `loadtest-{1..2000}@loadtest.local` / `loadtest-password`. They share a precomputed BCrypt hash; login still verifies passwords. Rerunning the seed inserts data again, so use it only once per fresh database. Kaggle runs it automatically.

- Browse chooses today or tomorrow, whichever first has Showtimes. Setup fails if both are empty; seat maps must contain 120 Seats.
- Book uses 12 Customers and separate Seat allocations to avoid conflicts. One Showtime is reserved for contention; the remaining 50,280 Showtime/Seat pairs give each VU 4,190 choices. Longer or higher-rate runs can exhaust them. The 12-VU cap is an allocation limit.
- Contention logs in 200 Customers and selects 10 available Seats. It counts seat conflicts separately from rejections because a Customer already has a Pending Booking. The notebook's SQL check must match successful bookings to stored Seats with no duplicates. It does not require all 10 Seats to be booked or synchronise a simultaneous race.

## Source bundle

From the repository root, choose a new output path outside the repository:

```bash
python3 loadtest/bundle.py -o "<new-output-path>/source.zip"
```

The helper copies tracked and untracked non-ignored files into the ZIP and keeps executable bits. It skips `.git/`, `target/`, `.env*`, `secrets/`, symlinks and special files, and will not overwrite an existing output. Check for secrets stored under other filenames, then upload to a private Dataset.

`CODE_BUNDLE="auto"` requires one expanded directory under `/kaggle/input` containing `loadtest-bundle.json`; aliases to the same directory count once. You can also give an explicit ZIP or expanded-root path. The root must contain the manifest beside `pom.xml`, `mvnw`, `.mvn/`, `src/` and `loadtest/`. Check the mounted path instead of guessing it from the dataset name.

The manifest records `HEAD` as `sourceCommit`, but the bundle can include uncommitted changes. Match the hash in `env.txt` to your snapshot: ZIP input hashes archive bytes; expanded input hashes sorted paths, executable flags and bytes. Git input has no bundle hash. The notebook keeps the source outside saved output.

<details>
<summary>Kaggle CLI: update an existing private Dataset and notebook</summary>

Use authenticated Kaggle CLI 2.2.4. Set staging directories outside the repository and replace the placeholders:

```bash
export DATASET_DIR=...
export KERNEL_DIR=...
export KERNEL_REF=...          # owner/notebook
export SOURCE_DATASET_REF=...  # owner/dataset
export OUT_DIR=...
mkdir -p "$DATASET_DIR" "$KERNEL_DIR" "$OUT_DIR"
kaggle kernels pull "$KERNEL_REF" -p "$KERNEL_DIR" -m
```

1. Put `dataset-metadata.json` in `DATASET_DIR`, with `id` matching `SOURCE_DATASET_REF`. Use flat upload metadata, not the `info` wrapper returned by `kaggle datasets metadata`.
2. Copy the repository notebook into `KERNEL_DIR`, set `CODE_BUNDLE="auto"` and the run parameters.
3. Update `kernel-metadata.json`: point `code_file` to the notebook; keep its identity fields; set `dataset_sources` to `["<owner>/<dataset>"]` without a version number. Set `is_private: true`, `enable_gpu: false`, `enable_tpu: false`, `enable_internet: true`. Never include credentials.
4. Build and publish the source. The upload directory should contain only the ZIP and metadata:

```bash
python3 loadtest/bundle.py -o "$DATASET_DIR/source.zip"
kaggle datasets version -p "$DATASET_DIR" -m "load-test source snapshot"
kaggle datasets status "$SOURCE_DATASET_REF" --format 'json(status,current_version_number)'
```

Wait for version creation and `ready` with the new version number. Keep both resources unchanged until output verification finishes; the notebook attaches the latest dataset version. Versioning preserves dataset privacy.

```bash
kaggle kernels push -p "$KERNEL_DIR"
kaggle kernels logs -f "$KERNEL_REF"
kaggle kernels status "$KERNEL_REF"
```

Record the version number from `Kernel version N successfully pushed`. Investigate upload warnings or errors before continuing. Wait until the status says `COMPLETE`; the status command can return exit code 0 even for `ERROR`.

```bash
kaggle kernels output "$KERNEL_REF" -p "$OUT_DIR"
```

Verify `env.txt` against your source commit and matching hash type. CLI logs/status/output use the base reference and latest version; use the saved version's **Output** tab for older results. Evidence URLs end in `/code/<owner>/<slug>/versions/<N>` and `/datasets/<owner>/<slug>/versions/<M>`; their version numbers are independent. Keep existing privacy and older versions; do not enable GPU/TPU.

</details>

## Add a scenario

Add `k6/<name>.js` and its name to the notebook's `SCENARIO` allowlist. Add it to `RUN_SCENARIOS` to include it in `all`.

Reuse `summaryOptions`, including its thresholds, `LOAD` request tags and `handleSummary`. Read `BASE_URL`, `RATE`, `DURATION` from `__ENV`; use `lib/customers.js` for seeded-Customer login.
