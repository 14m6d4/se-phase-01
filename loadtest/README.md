# Load test

The baseline load test runs on a Kaggle CPU notebook so phase 2 can repeat the workflow on the Kaggle CPU platform ([ADR 0003](../docs/adr/0003-load-test-on-kaggle-without-docker.md)). Compare each run against its recorded `results/env.txt` rather than assuming identical hardware. Kaggle has no Docker. The notebook therefore:

1. installs PostgreSQL with `apt`;
2. builds the service from this repository and runs it as a plain JAR;
3. loads it with k6 from the same machine.

| File | What it is |
|---|---|
| `kaggle.ipynb` | The harness: checks, PostgreSQL, build, service, k6, reports |
| `bundle.py` | Builds the private full-source ZIP and working-tree manifest |
| `test_loadtest.py` | Python and Node regression checks for the helper, notebook and k6 libraries |
| `k6/<scenario>.js` | One k6 scenario per file (`smoke`, `browse`, `book`, `contention`) |
| `seed.sql` | Load-test data: 3 cinemas x 4 auditoriums (10x12 seats), 20 movies, 7 future days of showtimes and 2,000 Customers |
| `k6/lib/` | Shared seeded-Customer login, seeded Showtime lookup, summary handling and conflict classification |
| `report.py` | Prints RPS, p50/p95/p99 and error rate from a `summary.json` |

## Seeded data

`loadtest/seed.sql` inserts, against the database the service is already using (run it once, on a fresh database; the inserts are not idempotent):

- 3 cinemas, each with 4 auditoriums of 10 rows x 12 seats (rows A–G STANDARD, H–I VIP, J COUPLE);
- 20 movies (durations 80–165 minutes);
- 5 showtimes a day per auditorium (09:00, 12:00, 15:00, 18:00, 21:00 Vietnam time) for the 7 days after the seed date — every showtime is strictly in the future when seeded, and the 3-hour slots cannot overlap;
- 2,000 CUSTOMER accounts `loadtest-{1..2000}@loadtest.local`, all sharing the password **`loadtest-password`** (one prehashed bcrypt value, so logging in costs the same as any login).

## One-time setup (Kaggle, optional)

1. **GitHub token (Git path only).** With `CODE_BUNDLE = None`, the private repository is cloned with a token.
   - Create a [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new).
   - Resource owner: `14m6d4`. Repository access: only `se-phase-01`. Permissions: **Contents: Read-only**.
2. **Kaggle account.** Only needed for the Kaggle path; if you do not have one (or prefer not to create it), use [Run a scenario locally](#run-a-scenario-locally) instead — the same notebook steps work on any machine with Java, PostgreSQL and k6. For Kaggle, enable **Internet** in the notebook and complete any account verification Kaggle requires before Internet access is available.
3. **Notebook.** On Kaggle: **Create → New Notebook**, then **File → Import Notebook** and upload `loadtest/kaggle.ipynb`.
4. **Notebook settings** (right-hand panel):
   - **Accelerator:** None (CPU, no GPU).
   - **Internet:** On.
   - **Add-ons → Secrets:** attach `GITHUB_TOKEN` only when using the Git path. A full-source bundle needs no GitHub credential.

Re-import the notebook whenever `kaggle.ipynb` changes. A previously imported copy keeps its old cells, so re-import it after the `HOLD` → `DURATION` rename instead of only re-running it.

### Full source input

By default, `CODE_BUNDLE = None` clones `GIT_REF` with the token. For unpublished changes, attach one full source snapshot as a **private Kaggle Dataset** and choose one input:

- `CODE_BUNDLE = "auto"` finds exactly one expanded source root below `/kaggle/input` whose top level contains `loadtest-bundle.json`. Mount aliases are deduplicated by resolved parent; zero or multiple roots stop with an error.
- An explicit ZIP path or explicit expanded source root.

The platform assigns the mount prefix; do not infer it from the dataset slug or assume a `source/` subdirectory. An explicit root must directly contain `loadtest-bundle.json` alongside repository files such as `pom.xml`, `mvnw`, `.mvn/`, `src/`, and `loadtest/`. Every source input needs this manifest:

```json
{"snapshotMode":"working-tree","sourceCommit":"<40-character git rev-parse HEAD>"}
```

Create the archive from the repository root with a new output path outside the repository:

```bash
python3 loadtest/bundle.py -o "<new-output-path>/source.zip"
```

The helper writes the manifest with `snapshotMode: working-tree` and the current `HEAD` as `sourceCommit`. It includes tracked and untracked non-ignored working-tree bytes, preserves executable bits, and omits `.git/`, `target/`, `.env*`, `secrets/`, symlinks and special files. It refuses an output path that already exists or is a symlink. Inspect the archive before uploading it to the private Dataset; the helper does not identify custom secret filenames.

The notebook copies validated input to `/tmp/se-phase-01`, outside saved results. `results/env.txt` records the source hash: `none` for Git, exact ZIP bytes for ZIP input, or a canonical fingerprint of sorted relative paths, executable flags and bytes for an expanded root. The accepted baseline records the exact ZIP and tree values.

## Kaggle CLI

The web UI above is optional. With the [Kaggle CLI 2.2.4](https://www.kaggle.com/docs/api#authentication) authenticated, set your own staging paths and existing references outside this repository. Replace every `...` placeholder before running:

```bash
export DATASET_DIR=...         # fresh private-dataset staging directory
export KERNEL_DIR=...          # existing kernel's metadata and notebook directory
export KERNEL_REF=...          # owner/kernel of the existing private kernel
export SOURCE_DATASET_REF=...  # owner/dataset of the existing private source dataset
export OUT_DIR=...             # new downloaded-output directory
mkdir -p "$DATASET_DIR" "$KERNEL_DIR" "$OUT_DIR"
```

Use the existing private dataset and kernel; do not create replacements. Prepare both sides before uploading anything. Coordinate exclusive updates to both resources for this workflow: after the uploads below, publish no additional versions until run completion and output verification, and ensure no other writer updates either resource during the workflow.

1. **Dataset metadata.** `DATASET_DIR` is where the source ZIP and a flat upload-format `dataset-metadata.json` are prepared. The metadata must have at least `{"id": "<owner>/<dataset>"}` matching `SOURCE_DATASET_REF`. Retain the existing title, subtitle and description if wanted. Do not copy the response of `kaggle datasets metadata`: it wraps fields in an `info` object and is not upload metadata. A new dataset version preserves the dataset's existing privacy; it does not read `isPrivate` from this file.
2. **Kernel staging.** If needed, pull the existing metadata and notebook:
   ```bash
   kaggle kernels pull "$KERNEL_REF" -p "$KERNEL_DIR" -m
   ```
   Copy `loadtest/kaggle.ipynb` into `$KERNEL_DIR` and set `code_file` to the copied notebook's filename. In its Parameters cell, set `CODE_BUNDLE = "auto"` plus the desired `SCENARIO`, `RATE` and `DURATION`. Keep the existing kernel `id`, `id_no`, title and language fields. Set `dataset_sources` to the one-element JSON array `["<owner>/<dataset>"]`, replacing the placeholder with the value of `SOURCE_DATASET_REF`. Set `is_private: true`, `enable_gpu: false`, `enable_tpu: false` and `enable_internet: true`. `dataset_sources` names the dataset, not a version; do not append a version number. Never print or commit credentials or tokens.
3. **Source ZIP.** From the repository root:
   ```bash
   python3 loadtest/bundle.py -o "$DATASET_DIR/source.zip"
   ```
   Once prepared, `DATASET_DIR` contains only `source.zip` and `dataset-metadata.json`.

Publish the dataset version:

```bash
kaggle datasets version -p "$DATASET_DIR" -m "load-test source snapshot"
```

The command prints a processing message and base URL, not the version number. Require the actual dataset-version creation message, not exit code 0 alone. Repeat the native status command while processing; require `status` = `ready` and record the actual `current_version_number` as `M`:

```bash
kaggle datasets status "$SOURCE_DATASET_REF" --format 'json(status,current_version_number)'
```

`status` `ready` reports the current version; it does not make `dataset_sources` select that version. Do not delete older versions, change privacy, or add a GPU or TPU. The kernel metadata `is_private` flag controls notebook privacy.

Push the kernel:

```bash
kaggle kernels push -p "$KERNEL_DIR"
```

Require the actual `Kernel version N successfully pushed` line. Exit code 0 alone is not success; stop on warnings or errors. `N` is the ordinal used in the version link, not a global `scriptVersionId`, and is not assumed equal to `M`.

Wait for completion:

```bash
kaggle kernels logs -f "$KERNEL_REF"
kaggle kernels status "$KERNEL_REF"
```

The logs command streams while the run is active; `kernels status` may report `RUNNING`, so repeat it while processing. Require the status text to contain `COMPLETE`, because `kernels status` also exits 0 when the run ends in `ERROR`.

Only after `COMPLETE`, download the output:

```bash
kaggle kernels output "$KERNEL_REF" -p "$OUT_DIR"
```

CLI 2.2.4 `kernels logs`, `status` and `output` use the base `$KERNEL_REF` and operate on the latest version; do not append `N`. After download, verify `results/env.txt` source commit and hash against the source snapshot published as `M`; compare only the hash kind recorded for that input, never a ZIP byte hash with an expanded-tree hash. Private version links require access; use your own source snapshot and account. The version-selector route is `/code/<owner>/<slug>/versions/<N>` for the kernel and `/datasets/<owner>/<slug>/versions/<M>` for the dataset.

## Run in the Kaggle UI

The **Kaggle CLI** workflow above is the non-interactive alternative.

1. Adjust the **Parameters** cell:
   - `GIT_REF`: branch, tag or commit to test (default `main`; ignored with `CODE_BUNDLE`).
   - `SCENARIO`: `all` runs browse, book and contention in order; or select `smoke`, `browse`, `book` or `contention`.
   - `RATE`: target iterations per second (actual HTTP RPS is in the report; scenarios may issue multiple requests per iteration).
   - `DURATION`: how long that rate is held, excluding the 30 s ramp-up and 10 s ramp-down.
   - `CODE_BUNDLE`: `None` clones `GIT_REF`; `"auto"` and explicit paths are described under [Full source input](#full-source-input).
2. Click **Save Version → Save & Run All (Commit)**. It runs unattended; the interactive editor stops after 20 minutes idle.
3. Open the saved version. The last cell prints the headline numbers. The **Output** tab has `results/`:
   - `<scenario>/summary.json`: full k6 summary per run; `summary.json` is also copied from the last scenario for compatibility.
   - `contention/contention.json` and `contention-verification.txt`: tested Showtime and SQL PASS/FAIL comparing k6 HTTP 201s with its booked-seat rows and checking duplicate seat rows.
   - `env.txt`: hardware (`nproc`, `free -h`, `lscpu`), source provenance/base commit/bundle hash, Java/JVM, PostgreSQL, Hikari pool.
   - `app.log`: the service's log.

The environment cell stops with an explanation if internet is off or (on the Git path) the secret is missing. If the service does not become healthy within 180 s, its log tail is printed.

### Reading the numbers

- Only requests tagged `phase:load` are reported. Setup's seeded-Customer logins (BCrypt) are excluded.
- **RPS** is measured HTTP requests per second averaged over the whole run, ramps and setup included; it differs from the configured iteration rate when a scenario makes multiple requests per iteration.
- **Dropped iterations > 0** means k6 could not start every scheduled iteration within the VU limit. Every scenario fails acceptance when any iterations are dropped; lowering the rate is a different test, not a passing result for the original rate.
- The service and k6 share the run's CPUs. Compare runs against the `results/env.txt` artifact from the same environment; do not assume two runs had identical hardware.
- Scenario threshold failures are reported after all selected scenarios have run. Every scenario requires all checks to pass, zero unexpected measured HTTP failures and zero dropped iterations. Book checks the hold/payment responses. Contention treats only `201` and `409` as expected and fails on malformed responses or unexpected HTTP statuses. The latency limits printed by `report.py` remain advisory.

The notebook seeds the database automatically after the service is healthy.

The accepted Kaggle measurements are recorded in [`docs/loadtest-baseline.md`](../docs/loadtest-baseline.md): kernel version 5 with private source dataset version 5. The run used the source snapshot identified there, a working-tree snapshot based on `01a71f7` that was not a clean checkout of that commit. Repeating the baseline requires that private source artifact; the Git base commit alone is insufficient.

## Scenarios

**`smoke`**: `setup()` logs in the first `maxVUs` pre-seeded Customers once with `http.batch`; each VU calls `GET /api/me` with its own token under `ramping-arrival-rate`: 30 s ramp to `RATE`, hold for `DURATION`, 10 s ramp down. Like the booking tests, it needs `seed.sql`.

**`browse`**: no login — anonymous users call the public endpoints. `setup()` picks the first Vietnam-local date (today or tomorrow) that has Showtimes and passes that date to the VUs; every iteration makes exactly two measured requests: `GET /api/showtimes?date=<selected date>` and then `GET /api/showtimes/{id}/seats` for one returned showtime (120 seats per map). Requires `seed.sql`, so there is something to browse; otherwise setup fails before any VU starts (k6 exit code 107, `no showtimes seeded for today or tomorrow`). Same `ramping-arrival-rate` shape as `smoke`.

**`book`**: setup logs in 12 seeded Customers with `http.batch`. Each VU owns a disjoint Showtime/Seat slice and uses `__ITER` for O(1) unique allocation. With the seed's 420 Showtimes, reserving one for contention leaves 50,280 slots—4,190 per VU, enough for all ~4,000 iterations of the default 50/s, 60s run even if one VU receives every iteration. `maxVUs=12` is the default allocation cap to keep those disjoint slices large enough; it is not a measured maximum-throughput claim. High per-iteration latency can still cause dropped iterations, and longer runs can exhaust a VU's slice.

**`contention`**: setup logs in 200 seeded Customers and selects 10 available Seats on the first seeded Showtime. VUs repeatedly try those same Seats; only HTTP `201`, the seat-taken `409` whose problem `seatIds` contains the sent Seat, and the `409 Customer already has a Pending Booking` are accepted and counted separately. That second 409 is a real business rule, not seat contention. After the run, the notebook requires at least one seat conflict, checks the conflict-counter totals, compares k6's `bookings_created` count to `booked_seat` rows for that Showtime, and rejects duplicate seat rows.

To add a scenario, create `loadtest/k6/<name>.js`, add `<name>` to the `SCENARIO` allowlist in the Parameters cell, then set `SCENARIO = "<name>"`; an unlisted name raises `Unknown SCENARIO`. `SCENARIO="all"` still runs only the three fixed scenarios in `RUN_SCENARIOS`; add a new name there only if it should run with `all`. The script must:

- spread `summaryOptions` into its `options`.
- re-export `handleSummary` from `k6/lib/summary.js`; wrap it only to add scenario-specific output.
- pass `LOAD` in the params of every request that should be measured.
- read `BASE_URL`, `RATE` and `DURATION` from `__ENV`, like `smoke.js` does.
- for authenticated load, use `lib/customers.js` so each VU gets one of the seeded Customers.

## Run a scenario locally

Run the standard-library allocation, conflict-classifier, browse/showtime, notebook and source-bundle assertions with `python3 loadtest/test_loadtest.py` (Python and Node required).

Useful for debugging a script without a Kaggle round trip (and the way to run without a Kaggle account). You need [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) (same version as `K6_VERSION` in the notebook), a PostgreSQL database and the service running, e.g. `docker compose up --build` (see the main README).

```bash
# Once per fresh database, after the service is healthy (Flyway applied):
psql "<your postgres url for the cinema database>" -f loadtest/seed.sql

mkdir -p loadtest/results            # k6 does not create it
k6 run -e RATE=20 -e DURATION=10s loadtest/k6/browse.js
python3 loadtest/report.py           # reads loadtest/results/summary.json
```

`loadtest/results/` is git-ignored.
