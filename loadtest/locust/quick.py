"""Quick A/B check: one scenario at one fixed rate, plus the database time of each statement.

  python3 loadtest/locust/quick.py contention 300 [--duration 30] [--warmup 15] [--label baseline]

Much faster than step.py (about a minute per scenario) and enough to compare two builds: run it on the
baseline image and on the change at the same rate, ideally the rate where the baseline first broke.
pg_stat_statements is reset after the warm-up, so the SQL table covers the measured window only.

Contention and race use a fresh reserved Showtime each run (STEP), book a fresh range of Seats
(SLOT_OFFSET), so runs need no database reset in between. The counters live in --state.
Results are appended to loadtest/results/quick/<label>.jsonl.
"""
import argparse
import json
import os
import shutil
import subprocess
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
CLASSES = {"account": "Account", "browse": "Browse", "book": "Book", "contention": "Contention", "race": "Race"}
COMPOSE = os.environ.get("COMPOSE", "docker compose -f docker-compose.yml -f loadtest/locust/compose.kaggle-like.yml")


def psql(sql):
    return subprocess.run(f"{COMPOSE} exec -T db psql -U cinema -d cinema -tA -F '|' -c \"{sql}\"", shell=True,
                          capture_output=True, text=True, check=True).stdout.strip()


def locust(scenario, rate, seconds, results, env, cpus):
    cmd = [shutil.which("locust") or "locust", "-f", str(HERE / "locustfile.py"), "--headless", "--only-summary",
           "--loglevel", "WARNING", "-u", str(rate), "-r", str(rate), "-t", f"{seconds}s", "--stop-timeout", "10",
           CLASSES[scenario]]
    if cpus and shutil.which("taskset"):
        cmd = ["taskset", "-c", cpus] + cmd
    env = {**os.environ, **env, "RESULTS": str(results), "CUSTOMERS": str(max(rate, 50))}
    subprocess.run(cmd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return json.loads(results.read_text())


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    p.add_argument("scenario", choices=CLASSES)
    p.add_argument("rate", type=int)
    p.add_argument("--duration", type=int, default=30)
    p.add_argument("--warmup", type=int, default=15)
    p.add_argument("--label", default="run")
    p.add_argument("--cpus", default=os.environ.get("LOAD_CPUS", "0"))
    p.add_argument("--state", default="loadtest/results/quick/state.json")
    a = p.parse_args()

    out = Path("loadtest/results/quick")
    out.mkdir(parents=True, exist_ok=True)
    state_path = Path(a.state)
    state = json.loads(state_path.read_text()) if state_path.exists() else {"contention": 0, "race": 0, "book": 0}

    def env():
        if a.scenario in ("contention", "race"):
            return {"STEP": str(state[a.scenario])}
        if a.scenario == "book":
            return {"SLOT_OFFSET": str(state["book"])}
        return {}

    def consume(result):
        if a.scenario in ("contention", "race"):
            state[a.scenario] += 1
            if state[a.scenario] >= 20:
                raise SystemExit("all 20 reserved Showtimes of this scenario are used; reset the database")
        elif a.scenario == "book":
            state["book"] += result["counters"].get("slots", 0)
        state_path.write_text(json.dumps(state))

    warm = locust(a.scenario, a.rate, a.warmup, out / "warmup.json", env(), a.cpus)
    consume(warm)
    psql("select pg_stat_statements_reset()")
    started = time.time()
    result = locust(a.scenario, a.rate, a.duration, out / "last.json", env(), a.cpus)
    consume(result)
    rows = psql("select round(total_exec_time)::int, calls, round(mean_exec_time::numeric, 3), "
                "left(regexp_replace(query, '\\s+', ' ', 'g'), 90) from pg_stat_statements "
                "where query not like '%pg_stat_statements%' order by total_exec_time desc limit 5")
    statements = [dict(zip(("total_ms", "calls", "mean_ms", "query"), r.split("|", 3))) for r in rows.splitlines() if r]

    record = {"label": a.label, "scenario": a.scenario, "rate": a.rate, "at": time.strftime("%H:%M:%S", time.localtime(started)),
              "achieved": result["steady_it_s"], "p50_ms": round(result["p50_ms"] or 0), "p95_ms": round(result["p95_ms"] or 0),
              "p99_ms": round(result["p99_ms"] or 0), "failures": result["failures"], "requests": result["requests"],
              "counters": result["counters"], "top_sql": statements}
    with open(out / f"{a.label}.jsonl", "a") as f:
        f.write(json.dumps(record) + "\n")

    ok = result["failures"] == 0 and result["steady_it_s"] >= 0.95 * a.rate and (result["p95_ms"] or 0) <= 200
    print(f"{a.label} {a.scenario}@{a.rate}: achieved {record['achieved']} it/s, p50 {record['p50_ms']} / "
          f"p95 {record['p95_ms']} / p99 {record['p99_ms']} ms, failures {record['failures']}"
          f" -> {'within' if ok else 'outside'} the ADR 0007 limits")
    for s in statements:
        print(f"  {s['total_ms']:>7} ms  {s['calls']:>7} calls  {s['mean_ms']:>7} ms/call  {s['query']}")


if __name__ == "__main__":
    main()
