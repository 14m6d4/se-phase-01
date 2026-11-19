# Local load test with Locust

A fast local loop before a Kaggle run. The k6 scenarios in `loadtest/k6` and the Kaggle notebook stay the reference baseline (ADR 0007); Locust runs the same five workflows on your own machine, so a change can be checked in minutes and only the promising ones go to Kaggle.

Local numbers are comparable only with other local runs on the same machine, never with Kaggle numbers.

## Stack that resembles Kaggle

`compose.kaggle-like.yml` pins the service and PostgreSQL to the same three CPUs (`APP_CPUS`, default `1-3`), uses PostgreSQL 14 as Kaggle does, enables `pg_stat_statements`, and serves the API on port 18080 (`APP_PORT`).

```bash
cp .env.example .env    # then set real values
C="docker compose -f docker-compose.yml -f loadtest/locust/compose.kaggle-like.yml"
$C up -d --build
$C exec -T db psql -v ON_ERROR_STOP=1 -U cinema -d cinema -q < loadtest/seed.sql   # once per fresh database
$C exec -T db psql -U cinema -d cinema -c "create extension if not exists pg_stat_statements"
```

Reset to a fresh database: `$C down -v`, then `up` and seed again. Do this before every comparison, because the scenarios use up seeded Seats and Showtimes.

## Run

```bash
pip install locust
export BASE_URL=http://localhost:18080
python3 loadtest/locust/step.py contention          # account, browse, book, contention or race
```

`step.py` runs a warm-up at 50 it/s, then 60-second steps at 50, 100, 200 … until one breaks, then the midpoint. A step breaks when p95 > 200 ms, more than 1% of measured requests fail, or fewer than 95% of the target iterations complete. Contention and race steps end with an SQL check: no Seat booked twice, and booked Seats equal created minus abandoned Bookings. Locust runs on CPU 0 (`--cpus`, or `LOAD_CPUS`), away from the stack.

Results go to `loadtest/results/locust/<scenario>/`: one JSON per step and `summary.json`. If a step log warns about Locust's CPU, the limit may be Locust's rather than the service's.

One scenario at a fixed rate:

```bash
locust -f loadtest/locust/locustfile.py --headless -u 200 -r 200 -t 60s Contention
```

## When the Locust run differs from k6

Locust is closed-loop: each user waits for its response before its next iteration. When the service slows down, Locust sends fewer requests, while k6's constant arrival rate keeps sending. The 95%-achieved rule catches the slowdown, but latency under overload is lower than k6 would show. Use Locust to compare two versions of the code, and k6 on Kaggle for the reported numbers.
