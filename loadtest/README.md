# Load test

The baseline load test runs on a Kaggle CPU notebook, so that phase 2 can repeat it on the same hardware ([ADR 0003](../docs/adr/0003-load-test-on-kaggle-without-docker.md)). Kaggle has no Docker. The notebook therefore:

1. installs PostgreSQL with `apt`;
2. builds the service from this repository and runs it as a plain JAR;
3. loads it with k6 from the same machine.

| File | What it is |
|---|---|
| `kaggle.ipynb` | The harness: checks, PostgreSQL, build, service, k6, reports |
| `k6/<scenario>.js` | One k6 scenario per file (`smoke.js`, `browse.js`) |
| `seed.sql` | Load-test data: 3 cinemas x 4 auditoriums (10x12 seats), 20 movies, 7 future days of showtimes and 2,000 Customers |
| `k6/lib/summary.js` | Summary shared by all scenarios: p(99), `summary.json`, the `phase:load` tag |
| `report.py` | Prints RPS, p50/p95/p99 and error rate from a `summary.json` |

## Seeded data

`loadtest/seed.sql` inserts, against the database the service is already using (run it once, on a fresh database; the inserts are not idempotent):

- 3 cinemas, each with 4 auditoriums of 10 rows x 12 seats (rows A–G STANDARD, H–I VIP, J COUPLE);
- 20 movies (durations 80–165 minutes);
- 5 showtimes a day per auditorium (09:00, 12:00, 15:00, 18:00, 21:00 Vietnam time) for the 7 days after the seed date — every showtime is strictly in the future when seeded, and the 3-hour slots cannot overlap;
- 2,000 CUSTOMER accounts `loadtest-{1..2000}@loadtest.local`, all sharing the password **`loadtest-password`** (one prehashed bcrypt value, so logging in costs the same as any login).

## One-time setup (Kaggle, optional)

1. **GitHub token.** The repository is private, so the notebook clones it with a token.
   - Create a [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new).
   - Resource owner: `14m6d4`. Repository access: only `se-phase-01`. Permissions: **Contents: Read-only**.
2. **Kaggle account.** Only needed for the Kaggle path; if you do not have one (or prefer not to create it), use [Run a scenario locally](#run-a-scenario-locally) instead — same notebook steps work on any machine with Java, PostgreSQL and k6. For Kaggle, phone-verify the account (Settings → Phone verification): Kaggle does not allow internet in notebooks without it.
3. **Notebook.** On Kaggle: **Create → New Notebook**, then **File → Import Notebook** and upload `loadtest/kaggle.ipynb`.
4. **Notebook settings** (right-hand panel):
   - **Accelerator:** None (CPU, no GPU).
   - **Internet:** On.
   - **Add-ons → Secrets:** add `GITHUB_TOKEN` with the repository read token, and tick it so this notebook can read it.

Re-import the notebook whenever `kaggle.ipynb` changes in the repository. `GIT_REF` controls the service code and the k6 scripts, but not the notebook itself. The notebook clones `GIT_REF` into a clean checkout outside the saved output (`/tmp/se-phase-01`, wiped and rebuilt every run), so no repository files ever end up in the notebook's Output tab.

## Run

1. Adjust the **Parameters** cell:
   - `GIT_REF`: branch, tag or commit to test (default `main`).
   - `SCENARIO`: runs `k6/<SCENARIO>.js`.
   - `RATE`: target requests per second.
   - `HOLD`: how long that rate is held.
2. Click **Save Version → Save & Run All (Commit)**. It runs unattended; the interactive editor stops after 20 minutes idle.
3. Open the saved version. The last cell prints the headline numbers. The **Output** tab has `results/`:
   - `summary.json`: full k6 summary.
   - `env.txt`: hardware (`nproc`, `free -h`, `lscpu`), commit, Java/JVM, PostgreSQL, Hikari pool.
   - `app.log`: the service's log.

The first cell stops with an explanation if internet is off or the secret is missing. If the service does not become healthy within 180 s, its log tail is printed.

### Reading the numbers

- Only requests tagged `phase:load` are reported. Setup's registrations and logins (BCrypt, and `409` on re-runs) are excluded.
- **RPS** is averaged over the whole run, ramps and setup included, so it is below `RATE`.
- **Dropped iterations > 0** means k6 ran out of VUs (`maxVUs`) and did not reach the target rate.
- The service and k6 share the same ~4 vCPUs, so compare runs only with the same hardware in `env.txt`.
- The smoke thresholds (error rate < 1 %, p95 < 500 ms) are printed as PASS/FAIL and never stop the notebook.

The notebook seeds the database automatically after the service is healthy.

## Scenarios

**`smoke`**: `setup()` registers 50 Customers `smoke-{0..49}@loadtest.local` (existing ones are reused) and logs each in once. Every VU then calls `GET /api/me` with its own token under `ramping-arrival-rate`: 30 s ramp to `RATE`, hold for `HOLD`, 10 s ramp down.

**`browse`**: no login — the measured endpoints are public. Every iteration calls `GET /api/showtimes?date=<today in Vietnam>` and then `GET /api/showtimes/{id}/seats` for one of the returned showtimes (120 seats per map). Requires `seed.sql` to have been run, so there is something to browse; otherwise only the list request is measured. Same `ramping-arrival-rate` shape as `smoke`.

To add a scenario, add `k6/<name>.js` and set `SCENARIO = "<name>"`. The script must:

- spread `summaryOptions` into its `options`;
- `export { handleSummary }` from `k6/lib/summary.js`;
- pass `LOAD` in the params of every request that should be measured;
- read `BASE_URL`, `RATE` and `HOLD` from `__ENV`, like `smoke.js` does.

## Run a scenario locally

Useful for debugging a script without a Kaggle round trip (and the way to run without a Kaggle account). You need [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) (same version as `K6_VERSION` in the notebook), a PostgreSQL database and the service running, e.g. `docker compose up --build` (see the main README).

```bash
# Once per fresh database, after the service is healthy (Flyway applied):
psql "<your postgres url for the cinema database>" -f loadtest/seed.sql

mkdir -p loadtest/results            # k6 does not create it
k6 run -e RATE=20 -e HOLD=10s loadtest/k6/browse.js
python3 loadtest/report.py           # reads loadtest/results/summary.json
```

`loadtest/results/` is git-ignored.
