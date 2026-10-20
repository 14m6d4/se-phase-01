# Load test

The baseline load test runs on a Kaggle CPU notebook, so that phase 2 can repeat it on the same hardware ([ADR 0003](../docs/adr/0003-load-test-on-kaggle-without-docker.md)). Kaggle has no Docker. The notebook therefore:

1. installs PostgreSQL with `apt`;
2. builds the service from this repository and runs it as a plain JAR;
3. loads it with k6 from the same machine.

| File | What it is |
|---|---|
| `kaggle.ipynb` | The harness: checks, PostgreSQL, build, service, k6, reports |
| `k6/<scenario>.js` | One k6 scenario per file (`smoke`, `browse`, `book`, `contention`) |
| `seed.sql` | Load-test data: 3 cinemas x 4 auditoriums (10x12 seats), 20 movies, 7 future days of showtimes and 2,000 Customers |
| `k6/lib/` | Shared seeded-Customer login, seeded Showtime lookup and summary handling |
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
2. **Kaggle account.** Only needed for the Kaggle path; if you do not have one (or prefer not to create it), use [Run a scenario locally](#run-a-scenario-locally) instead — same notebook steps work on any machine with Java, PostgreSQL and k6. For Kaggle, phone-verify the account (Settings → Phone verification): Kaggle does not allow internet in notebooks without it.
3. **Notebook.** On Kaggle: **Create → New Notebook**, then **File → Import Notebook** and upload `loadtest/kaggle.ipynb`.
4. **Notebook settings** (right-hand panel):
   - **Accelerator:** None (CPU, no GPU).
   - **Internet:** On.
   - **Add-ons → Secrets:** attach `GITHUB_TOKEN` only when using the Git path. A full-source bundle needs no GitHub credential.

Re-import the notebook whenever `kaggle.ipynb` changes. A previously imported copy keeps its old cells, so re-import it after the `HOLD` → `DURATION` rename instead of only re-running it. By default, `CODE_BUNDLE = None` preserves the token-authenticated clone of `GIT_REF` into `/tmp/se-phase-01`. For unpublished changes, attach a **private Kaggle Dataset** containing a full source snapshot and set `CODE_BUNDLE` to either the ZIP path (for example `/kaggle/input/<dataset>/source.zip`) or the expanded source-root directory (for example `/kaggle/input/<dataset>/source`). The selected directory must directly contain `loadtest-bundle.json` alongside repository-root files such as `pom.xml`, `mvnw`, `.mvn/`, `src/`, and `loadtest/`.

```json
{"snapshotMode":"working-tree","sourceCommit":"<40-character git rev-parse HEAD>"}
```

Create the ZIP from the repository root with this standard-library script; it includes tracked and new non-ignored files, preserves executable bits, and writes the manifest without adding it to the worktree:

```bash
python3 - <<'PY'
import json, subprocess, zipfile
from pathlib import Path, PurePosixPath

files = subprocess.check_output(["git", "ls-files", "-co", "--exclude-standard", "-z"]).decode().split("\0")
with zipfile.ZipFile("source.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for name in filter(None, files):
        path = Path(name)
        if path.name == "source.zip":
            continue
        if any(part in {".git", "target", "secrets"} or part.startswith(".env") for part in PurePosixPath(name).parts):
            continue
        if not path.is_file() or path.is_symlink():
            continue
        info = zipfile.ZipInfo(name)
        info.create_system = 3
        info.compress_type = zipfile.ZIP_DEFLATED
        mode = 0o755 if path.stat().st_mode & 0o111 else 0o644
        info.external_attr = (0o100000 | mode) << 16
        z.writestr(info, path.read_bytes())
    z.writestr("loadtest-bundle.json", json.dumps({
        "snapshotMode": "working-tree",
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    }))
PY
```

Attach the ZIP to a **private Kaggle Dataset**. Kaggle may expand it into a directory instead of mounting the archive; use `!find /kaggle/input -name loadtest-bundle.json -print` to inspect the attached files, then set `CODE_BUNDLE` to the manifest's parent directory. The notebook requires that explicit root and will not guess between datasets. ZIP input remains supported. Both paths copy validated files to `/tmp/se-phase-01`, outside the saved `/kaggle/working/results`; neither reconstructs a ZIP in notebook output. Omit `.git/`, `target/`, `.env*`, and secrets; symlinks and special files are rejected. `results/env.txt` records the manifest commit and bundle hash: ZIP mode hashes exact archive bytes; expanded-directory mode hashes a deterministic fingerprint of sorted relative regular-file paths, executable flags, and file bytes; Git mode reports `none`.

## Run

1. Adjust the **Parameters** cell:
   - `GIT_REF`: branch, tag or commit to test (default `main`; ignored with `CODE_BUNDLE`).
   - `SCENARIO`: `all` runs browse, book and contention in order; or select `smoke`, `browse`, `book` or `contention`.
   - `RATE`: target iterations per second (actual HTTP RPS is in the report; scenarios may issue multiple requests per iteration).
   - `DURATION`: how long that rate is held.
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
- **Dropped iterations > 0** means k6 ran out of VUs (`maxVUs`) and did not reach the target rate.
- The service and k6 share the same ~4 vCPUs, so compare runs only with the same hardware in `env.txt`.
- Scenario threshold failures are reported after all selected scenarios have run. Book requires zero failed measured requests and valid hold/payment responses. Contention treats only `201` and `409` as expected and fails on malformed responses or unexpected HTTP statuses.

The notebook seeds the database automatically after the service is healthy.

The accepted Kaggle measurements are recorded in [`docs/loadtest-baseline.md`](../docs/loadtest-baseline.md). They cover the scenarios and SQL verifier in the uploaded run, not the expanded-directory input and CI regression changes added afterward; those local changes have not been rerun on Kaggle. The recorded kernel-v2 hash is of ZIP archive bytes; expanded-directory mode hashes a canonical file tree, so those hash values are not directly comparable.

## Scenarios

**`smoke`**: `setup()` logs in the first `maxVUs` pre-seeded Customers once with `http.batch`; each VU calls `GET /api/me` with its own token under `ramping-arrival-rate`: 30 s ramp to `RATE`, hold for `DURATION`, 10 s ramp down. Like the booking tests, it needs `seed.sql`.

**`browse`**: no login — the measured endpoints are public. Every iteration calls `GET /api/showtimes?date=<today in Vietnam>` and then `GET /api/showtimes/{id}/seats` for one of the returned showtimes (120 seats per map). Requires `seed.sql` to have been run, so there is something to browse; otherwise setup fails the run before any VU starts (k6 exit code 107, `no showtimes seeded for today or tomorrow`). Same `ramping-arrival-rate` shape as `smoke`.

**`book`**: setup logs in 12 seeded Customers with `http.batch`. Each VU owns a disjoint Showtime/Seat slice and uses `__ITER` for O(1) unique allocation. With the seed's 420 Showtimes, reserving one for contention leaves 50,280 slots—4,190 per VU, enough for all ~4,000 iterations of the default 50/s, 60s run even if one VU receives every iteration. `maxVUs=12` is the default allocation cap to keep those disjoint slices large enough; it is not a measured maximum-throughput claim. High per-iteration latency can still cause dropped iterations, and longer runs can exhaust a VU's slice.

**`contention`**: setup logs in 200 seeded Customers and selects 10 available Seats on the first seeded Showtime. VUs repeatedly try those same Seats; only HTTP `201`, `409 Seat already taken`, and `409 Customer already has a Pending Booking` are accepted and counted separately. That second 409 is a real business rule, not seat contention. After the run, the notebook requires at least one seat conflict, checks the conflict-counter totals, compares k6's `bookings_created` count to `booked_seat` rows for that Showtime, and rejects duplicate seat rows.

To add a scenario, add `k6/<name>.js` and set `SCENARIO = "<name>"`. The script must:

- spread `summaryOptions` into its `options`;
- re-export `handleSummary` from `k6/lib/summary.js` (wrap it only to add scenario-specific output);
- pass `LOAD` in the params of every request that should be measured;
- read `BASE_URL`, `RATE` and `DURATION` from `__ENV`, like `smoke.js` does.
- for authenticated load, use `lib/customers.js` so each VU gets one of the seeded Customers.

## Run a scenario locally

Run the standard-library allocation and notebook-verifier assertions with `python3 loadtest/test_loadtest.py` (Python and Node required).

Useful for debugging a script without a Kaggle round trip (and the way to run without a Kaggle account). You need [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) (same version as `K6_VERSION` in the notebook), a PostgreSQL database and the service running, e.g. `docker compose up --build` (see the main README).

```bash
# Once per fresh database, after the service is healthy (Flyway applied):
psql "<your postgres url for the cinema database>" -f loadtest/seed.sql

mkdir -p loadtest/results            # k6 does not create it
k6 run -e RATE=20 -e DURATION=10s loadtest/k6/browse.js
python3 loadtest/report.py           # reads loadtest/results/summary.json
```

`loadtest/results/` is git-ignored.
