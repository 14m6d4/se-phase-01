# Load test

The baseline load test runs on a Kaggle CPU notebook, so that phase 2 can repeat it on the same hardware ([ADR 0003](../docs/adr/0003-load-test-on-kaggle-without-docker.md)). Kaggle has no Docker. The notebook therefore:

1. installs PostgreSQL with `apt`;
2. builds the service from this repository and runs it as a plain JAR;
3. loads it with k6 from the same machine.

| File | What it is |
|---|---|
| `kaggle.ipynb` | The harness: checks, PostgreSQL, build, service, k6, reports |
| `k6/<scenario>.js` | One k6 scenario per file (`smoke.js` so far) |
| `k6/lib/summary.js` | Summary shared by all scenarios: p(99), `summary.json`, the `phase:load` tag |
| `report.py` | Prints RPS, p50/p95/p99 and error rate from a `summary.json` |

## One-time setup

1. **GitHub token.** The repository is private, so the notebook clones it with a token.
   - Create a [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new).
   - Resource owner: `14m6d4`. Repository access: only `se-phase-01`. Permissions: **Contents: Read-only**.
2. **Kaggle account.** Phone-verify it (Settings → Phone verification). Kaggle does not allow internet in notebooks without it.
3. **Notebook.** On Kaggle: **Create → New Notebook**, then **File → Import Notebook** and upload `loadtest/kaggle.ipynb`.
4. **Notebook settings** (right-hand panel):
   - **Accelerator:** None (CPU).
   - **Internet:** On.
   - **Add-ons → Secrets:** add `GITHUB_TOKEN` with the token, and tick it so this notebook can read it.

Re-import the notebook whenever `kaggle.ipynb` changes in the repository. `GIT_REF` controls the service code and the k6 scripts, but not the notebook itself.

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
- **RPS** is averaged over the whole run, ramps included, so it is below `RATE`.
- **Dropped iterations > 0** means k6 ran out of VUs (`maxVUs`) and did not reach the target rate.
- The service and k6 share the same ~4 vCPUs, so compare runs only with the same hardware in `env.txt`.
- The smoke thresholds (error rate < 1 %, p95 < 500 ms) are printed as PASS/FAIL and never stop the notebook.

## Scenarios

**`smoke`**: `setup()` registers 50 Customers `smoke-{0..49}@loadtest.local` (existing ones are reused) and logs each in once. Every VU then calls `GET /api/me` with its own token under `ramping-arrival-rate`: 30 s ramp to `RATE`, hold for `HOLD`, 10 s ramp down.

To add a scenario, add `k6/<name>.js` and set `SCENARIO = "<name>"`. The script must:

- spread `summaryOptions` into its `options`;
- `export { handleSummary }` from `k6/lib/summary.js`;
- pass `LOAD` in the params of every request that should be measured;
- read `BASE_URL`, `RATE` and `HOLD` from `__ENV`, like `smoke.js` does.

## Run a scenario locally

Useful for debugging a script without a Kaggle round trip. You need [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) (same version as `K6_VERSION` in the notebook) and the service running, e.g. `docker compose up --build` (see the main README).

```bash
mkdir -p loadtest/results            # k6 does not create it
k6 run -e RATE=20 -e HOLD=10s loadtest/k6/smoke.js
python3 loadtest/report.py           # reads loadtest/results/summary.json
```

`loadtest/results/` is git-ignored.
