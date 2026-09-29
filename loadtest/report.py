"""Prints the headline numbers of a k6 run from the summary JSON written by k6/lib/summary.js.

Usage: python3 loadtest/report.py [loadtest/results/summary.json]
"""
import json
import sys

# Smoke thresholds: reported as PASS/FAIL, never used to abort the run.
MAX_ERROR_RATE = 0.01
MAX_P95_MS = 500


def LOAD(name):
    """The sub-metric of requests tagged phase:load (see k6/lib/summary.js); setup's logins are excluded."""
    return f"{name}{{phase:load}}"


def metric(data, name, stat):
    return data["metrics"].get(name, {}).get("values", {}).get(stat)


def fmt(value, unit=""):
    return "n/a" if value is None else f"{value:,.2f}{unit}"


def fmt_count(value):
    return "n/a" if value is None else f"{value:,.0f}"


def main(path):
    with open(path) as f:
        data = json.load(f)

    rps = metric(data, LOAD("http_reqs"), "rate")
    p50 = metric(data, LOAD("http_req_duration"), "med")
    p95 = metric(data, LOAD("http_req_duration"), "p(95)")
    p99 = metric(data, LOAD("http_req_duration"), "p(99)")
    error_rate = metric(data, LOAD("http_req_failed"), "rate")
    requests = metric(data, LOAD("http_reqs"), "count")
    # Only present when k6 ran out of VUs for the target rate.
    dropped = metric(data, "dropped_iterations", "count") or 0

    rows = [
        ("Requests", fmt_count(requests)),
        # Averaged over the whole run, ramps and setup included, so below the target rate.
        ("RPS (run average)", fmt(rps)),
        ("p50", fmt(p50, " ms")),
        ("p95", fmt(p95, " ms")),
        ("p99", fmt(p99, " ms")),
        ("Error rate", "n/a" if error_rate is None else f"{error_rate:.2%}"),
        ("Dropped iterations", fmt_count(dropped)),
    ]
    width = max(len(label) for label, _ in rows)
    for label, value in rows:
        print(f"{label:<{width}}  {value}")

    print()
    checks = [
        (f"error rate < {MAX_ERROR_RATE:.0%}", error_rate is not None and error_rate < MAX_ERROR_RATE),
        (f"p95 < {MAX_P95_MS} ms", p95 is not None and p95 < MAX_P95_MS),
    ]
    for label, ok in checks:
        print(f"{'PASS' if ok else 'FAIL'}  {label}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "loadtest/results/summary.json")
