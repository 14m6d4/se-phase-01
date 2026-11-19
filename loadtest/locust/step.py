"""Stepped capacity run with Locust against a local stack, judged by the ADR 0007 rules.

  python3 loadtest/locust/step.py contention [--start 50] [--max 6400] [--duration 60] [--out DIR]

Runs an unmeasured warm-up at --start, then one Locust run per rate (50, 100, 200, ... doubling) until a
step breaks, then one step at the midpoint. A step breaks when p95 > 200 ms, more than 1% of measured
requests fail, or fewer than 95% of the target iterations complete. Contention and race steps end with
an SQL check: no Seat booked twice, and booked Seats equal created minus abandoned Bookings.

Run it from the repository root with the stack up (docker compose, see loadtest/locust/README.md).
Locust runs on --cpus (default 0) so it does not compete with the service and PostgreSQL.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CLASSES = {"account": "Account", "browse": "Browse", "book": "Book", "contention": "Contention", "race": "Race"}
COMPOSE = os.environ.get("COMPOSE", "docker compose -f docker-compose.yml -f loadtest/locust/compose.kaggle-like.yml")


def psql(sql):
    out = subprocess.run(f"{COMPOSE} exec -T db psql -U cinema -d cinema -tAc \"{sql}\"", shell=True,
                         capture_output=True, text=True, check=True)
    return out.stdout.strip()


def run_step(scenario, rate, duration, out, env_extra, cpus):
    out.mkdir(parents=True, exist_ok=True)
    results = out / f"{rate}.json"
    env = {**os.environ, **env_extra, "RESULTS": str(results), "CUSTOMERS": str(max(rate, 50))}
    locust = shutil.which("locust") or "locust"
    cmd = [locust, "-f", str(HERE / "locustfile.py"), "--headless", "--only-summary", "--loglevel", "WARNING",
           "-u", str(rate), "-r", str(rate), "-t", f"{duration}s", "--stop-timeout", "10", CLASSES[scenario]]
    if cpus and shutil.which("taskset"):
        cmd = ["taskset", "-c", cpus] + cmd
    log = out / f"{rate}.log"
    with open(log, "w") as f:
        subprocess.run(cmd, env=env, stdout=f, stderr=subprocess.STDOUT)
    if not results.exists():
        sys.exit(f"step {rate} wrote no results; see {log}")
    result = json.loads(results.read_text())
    result["locust_cpu_warning"] = "CPU usage above" in log.read_text()
    return result


def judge(result, rate, duration):
    reasons = []
    if result["requests"] == 0:
        reasons.append("no measured requests")
    else:
        if result["p95_ms"] > 200:
            reasons.append(f"p95 {result['p95_ms']:.0f} ms")
        if result["failures"] / result["requests"] > 0.01:
            reasons.append(f"failures {100 * result['failures'] / result['requests']:.1f}%")
    achieved = result["steady_it_s"]
    if achieved < 0.95 * rate:
        reasons.append(f"achieved {achieved:.0f} it/s")
    return achieved, reasons


def verify(result):
    sid = result.get("showtime_id")
    if not sid:
        return None
    duplicates = psql(f"select count(*) from (select seat_id from booked_seat where showtime_id = '{sid}' "
                      f"group by seat_id having count(*) > 1) d")
    booked = int(psql(f"select count(*) from booked_seat where showtime_id = '{sid}'"))
    c = result["counters"]
    expected = c.get("bookings_created", 0) - c.get("bookings_abandoned", 0)
    ok = duplicates == "0" and booked == expected
    return f"{'PASS' if ok else 'FAIL'}: duplicates={duplicates}, booked={booked}, created-abandoned={expected}"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("scenario", choices=CLASSES)
    parser.add_argument("--start", type=int, default=50)
    parser.add_argument("--max", type=int, default=6400)
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--out", default="loadtest/results/locust")
    parser.add_argument("--cpus", default=os.environ.get("LOAD_CPUS", "0"))
    args = parser.parse_args()
    out = Path(args.out) / args.scenario
    out.mkdir(parents=True, exist_ok=True)

    # book: pairs used by earlier runs on this database; contention/race: reserved Showtimes already used.
    step_index = int(os.environ.get("STEP", "0"))
    slot_offset = int(os.environ.get("SLOT_OFFSET", "0"))

    def env_for_step():
        return {"STEP": str(step_index), "SLOT_OFFSET": str(slot_offset)}

    print(f"warm-up at {args.start} it/s (not judged)")
    warm = run_step(args.scenario, args.start, args.duration, out / "warmup", env_for_step(), args.cpus)
    step_index += 1
    slot_offset += warm["counters"].get("slots", 0)

    rows, last_held, broken = [], None, None
    rates = []
    rate = args.start
    while rate <= args.max:
        rates.append(rate)
        rate *= 2

    def one(rate):
        nonlocal step_index, slot_offset
        result = run_step(args.scenario, rate, args.duration, out, env_for_step(), args.cpus)
        step_index += 1
        slot_offset += result["counters"].get("slots", 0)
        achieved, reasons = judge(result, rate, args.duration)
        check = verify(result) if args.scenario in ("contention", "race") else None
        if check and check.startswith("FAIL"):
            reasons.append("SQL verification failed")
        row = {"rate": rate, "achieved": round(achieved), "p95_ms": result["p95_ms"], "p99_ms": result["p99_ms"],
               "failures": result["failures"], "requests": result["requests"], "verdict": "broke" if reasons else "held",
               "why": ", ".join(reasons), "verification": check, "counters": result["counters"],
               "locust_cpu_warning": result["locust_cpu_warning"]}
        rows.append(row)
        print(f"{rate:>6} it/s  {row['verdict']:<6} achieved {row['achieved']:>6}  p95 {row['p95_ms']} ms  "
              f"p99 {row['p99_ms']} ms  {row['why']}  {check or ''}"
              f"{'  [locust CPU warning]' if row['locust_cpu_warning'] else ''}")
        return not reasons

    for rate in rates:
        if one(rate):
            last_held = rate
        else:
            broken = rate
            break
    if broken and last_held:
        midpoint = (last_held + broken) // 2
        if midpoint not in (last_held, broken) and one(midpoint):
            last_held = midpoint

    summary = {"scenario": args.scenario, "highest_held": last_held, "first_broken": broken, "steps": rows}
    (out / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\n{args.scenario}: highest held {last_held} it/s, first broken {broken} it/s -> {out / 'summary.json'}")
    if any(r["verification"] and r["verification"].startswith("FAIL") for r in rows):
        sys.exit("SQL verification failed: a Seat was booked twice or the counts do not add up")


if __name__ == "__main__":
    main()
