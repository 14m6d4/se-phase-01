# Kaggle load-test capacity baseline

Two public Kaggle CPU notebooks ran the same source and protocol on 2026-10-29. Both completed all five scenarios.

- [Run 1, canonical notebook version 8](https://www.kaggle.com/code/tunichdi/se-phase-01-issue19-baseline/versions/8)
- [Run 2, independent notebook version 1](https://www.kaggle.com/code/tunichdi/se-phase-01-capacity-baseline-2/versions/1)

The source dataset `tunichdi/se-phase-01-issue19-source` is private and was unchanged at version 8. The two notebooks are public, so their code and saved outputs are readable without access to the source dataset. Raw output files remain outside Git.

## Method and source

The protocol is [ADR 0007](adr/0007-capacity-baseline-by-stepped-arrival-rate-runs.md). Each scenario has an unmeasured warm-up at 50 iterations/s, then measured 60-second steps at 50, 100, 200 and so on. If a step breaks, the notebook runs the midpoint between the last held step and the broken step. A step holds only when its p95 is at most 200 ms, unexpected request errors are at most 1%, checks are at least 99%, no iterations are dropped, and achieved iterations/s is at least 95% of target. Book exhaustion is reported separately from a broken step. Neither run exhausted the book seed.

The target is the configured iteration rate. Achieved it/s is completed workflow iterations per second. RPS counts measured HTTP requests; browse and book each make two requests per iteration.

| Item | Run 1 | Run 2 |
|---|---|---|
| Kernel | `tunichdi/se-phase-01-issue19-baseline`, version 8 | `tunichdi/se-phase-01-capacity-baseline-2`, version 1 |
| Report timestamp | 2026-10-29 07:38:05 UTC | 2026-10-29 07:56:03 UTC |
| Source commit | `4d40ac3add0fa3f8d26e8a2ac8cc4ef5fa138e45` | `4d40ac3add0fa3f8d26e8a2ac8cc4ef5fa138e45` |
| Source bundle | Expanded tree SHA-256 `bca958516e03508b624dabd5a4fefd84fccd66c8e5f5e51c4078d0de228d16e8` | Expanded tree SHA-256 `bca958516e03508b624dabd5a4fefd84fccd66c8e5f5e51c4078d0de228d16e8` |
| Staged notebook | SHA-256 `22912b24a77df0d6762c1e2c9b7d7e9e82f07175e6d26ddfad3e59784841e9cf` | SHA-256 `22912b24a77df0d6762c1e2c9b7d7e9e82f07175e6d26ddfad3e59784841e9cf` |
| Source dataset | `tunichdi/se-phase-01-issue19-source`, private version 8 | `tunichdi/se-phase-01-issue19-source`, private version 8 |
| Kernel metadata | Public CPU notebook; GPU false; TPU false; internet true | Public CPU notebook; GPU false; TPU false; internet true |
| Parameters | `SCENARIO=all`, `START_RATE=50`, `MAX_RATE=6400`, `DURATION=60s`, `CODE_BUNDLE=auto` | Same |
| Hardware | 4 online CPUs, Intel Xeon @ 2.20 GHz, 31 GiB RAM | 4 online CPUs, Intel Xeon @ 2.20 GHz, 31 GiB RAM |
| CPU affinity | k6 and notebook on CPU 0; service and PostgreSQL on CPUs 1-3 | k6 and notebook on CPU 0; service and PostgreSQL on CPUs 1-3 |
| Runtime | OpenJDK 21.0.12.1, G1 GC, no JVM flags; PostgreSQL 14.24; Hikari maximum 10 | Same |
| Load generator | k6 v2.3.0 | k6 v2.3.0 |

Both saved notebooks have the same cell content. The source bundle hash in each `env.txt` is the expanded tree hash above, which includes relative paths, executable flags and file bytes. It is not a ZIP archive hash.

## Summary

![Capacity baseline summary showing the highest held target for both runs and all five scenarios](loadtest/summary.png)

The highest held target is the max sustainable throughput for that run. A value of `none` means no measured step held.

| Scenario | Workflow | Run 1 highest held target (it/s) | Run 2 highest held target (it/s) | Run 1 first non-held step (it/s) | Run 2 first non-held step (it/s) |
|---|---|---:|---:|---:|---:|
| account | Read the signed-in Customer's Account | 1,200 | 1,200 | 1,600 | 1,600 |
| browse | List Showtimes and open a seat map | 400 | 400 | 600 | 600 |
| book | Hold an unused Seat and pay | 50 | none | 75 | 50 |
| contention | Customers try to hold the same 10 held Seats | 200 | 400 | 300 | 600 |
| race | Customers race for the same free Seats; winners abandon | 200 | 300 | 300 | 400 |

Run 2's `book` step at 50 iterations/s dropped three iterations, so it has no held step. Its p95 was 8.28 ms, checks were 99.98%, errors were 0%, k6 CPU was 9%, Java CPU was 34%, PostgreSQL CPU was 13%, Hikari had no pending connections, and iowait was 6.20%. The result does not isolate a service bottleneck. Run 1's first book break was also caused only by dropped iterations, with one drop at 75 iterations/s. No book step exhausted seeded Seats.

## Scenario results

### Account

![Account scenario overlay for throughput, latency, CPU, connection pool, PostgreSQL waits, and both runs](loadtest/account.png)

| Run | target it/s | achieved it/s | RPS | p50 ms | p95 ms | p99 ms | errors | checks | dropped | busy 409 | result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Run 1 | 50 | 50.0 | 50.0 | 2.48 | 3.18 | 4.98 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 100 | 100.0 | 100.0 | 1.94 | 2.40 | 4.34 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 200 | 200.0 | 200.0 | 1.66 | 2.03 | 3.94 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 400 | 400.0 | 400.0 | 1.38 | 2.23 | 4.44 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 800 | 800.0 | 800.0 | 1.36 | 3.56 | 16.50 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 1,200 | 1,200.0 | 1,200.0 | 1.46 | 177.36 | 300.44 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 1,600 | 1,593.2 | 1,593.2 | 1.65 | 734.29 | 960.64 | 0.00% | 100.00% | 411 | 0 | broke: p95 > 200 ms, dropped iterations |
| Run 2 | 50 | 50.0 | 50.0 | 2.07 | 2.77 | 4.58 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 100 | 100.0 | 100.0 | 1.44 | 1.97 | 3.85 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 200 | 200.0 | 200.0 | 1.18 | 1.68 | 3.64 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 400 | 400.0 | 400.0 | 1.05 | 1.95 | 4.45 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 800 | 800.0 | 800.0 | 1.08 | 3.70 | 17.60 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 1,200 | 1,200.0 | 1,200.0 | 1.17 | 96.61 | 226.91 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 1,600 | 1,596.2 | 1,596.2 | 1.53 | 706.20 | 943.82 | 0.00% | 100.00% | 226 | 0 | broke: p95 > 200 ms, dropped iterations |

### Browse

![Browse scenario overlay for throughput, latency, CPU, connection pool, PostgreSQL waits, and both runs](loadtest/browse.png)

| Run | target it/s | achieved it/s | RPS | p50 ms | p95 ms | p99 ms | errors | checks | dropped | busy 409 | result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Run 1 | 50 | 50.0 | 100.0 | 3.16 | 4.22 | 6.04 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 100 | 100.0 | 200.0 | 2.98 | 4.93 | 7.08 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 200 | 200.0 | 400.0 | 2.95 | 9.95 | 32.33 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 400 | 400.1 | 800.2 | 48.88 | 131.54 | 179.77 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 600 | 419.5 | 838.9 | 1,060.12 | 1,764.66 | 1,880.22 | 0.00% | 100.00% | 10,833 | 0 | broke: p95 > 200 ms, dropped iterations, achieved < 95% of target |
| Run 1 | 800 | 426.6 | 853.2 | 1,273.35 | 2,060.38 | 2,215.26 | 0.00% | 100.00% | 22,350 | 0 | broke: p95 > 200 ms, dropped iterations, achieved < 95% of target |
| Run 2 | 50 | 50.0 | 100.0 | 2.52 | 3.85 | 5.81 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 100 | 100.0 | 200.0 | 2.33 | 4.62 | 7.74 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 200 | 200.0 | 400.0 | 2.26 | 8.69 | 26.03 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 400 | 400.1 | 800.1 | 38.50 | 128.44 | 186.93 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 600 | 430.3 | 860.6 | 1,032.48 | 1,739.33 | 1,920.66 | 0.00% | 100.00% | 10,161 | 0 | broke: p95 > 200 ms, dropped iterations, achieved < 95% of target |
| Run 2 | 800 | 433.7 | 867.3 | 1,167.05 | 1,920.25 | 2,098.56 | 0.00% | 100.00% | 21,823 | 0 | broke: p95 > 200 ms, dropped iterations, achieved < 95% of target |

### Book

![Book scenario overlay for throughput, latency, CPU, connection pool, PostgreSQL waits, and both runs](loadtest/book.png)

| Run | target it/s | achieved it/s | RPS | p50 ms | p95 ms | p99 ms | errors | checks | dropped | busy 409 | result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Run 1 | 50 | 50.0 | 100.0 | 7.50 | 9.89 | 19.50 | 0.00% | 99.98% | 0 | 0 | held |
| Run 1 | 75 | 75.0 | 150.0 | 6.92 | 9.29 | 73.71 | 0.00% | 100.00% | 1 | 0 | broke: dropped iterations |
| Run 1 | 100 | 98.7 | 197.3 | 6.83 | 9.83 | 146.84 | 0.00% | 99.99% | 80 | 0 | broke: dropped iterations |
| Run 2 | 50 | 50.0 | 99.9 | 5.76 | 8.28 | 83.75 | 0.00% | 99.98% | 3 | 0 | broke: dropped iterations |

### Contention

![Contention scenario overlay for throughput, latency, CPU, connection pool, PostgreSQL waits, conflicts, and both runs](loadtest/contention.png)

| Run | target it/s | achieved it/s | RPS | p50 ms | p95 ms | p99 ms | errors | checks | dropped | busy 409 | result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Run 1 | 50 | 50.0 | 50.0 | 10.42 | 13.02 | 16.26 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 100 | 100.0 | 100.0 | 8.04 | 10.24 | 12.91 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 200 | 200.0 | 200.0 | 9.31 | 16.63 | 23.77 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 300 | 300.0 | 300.0 | 14.99 | 453.17 | 1,056.12 | 0.00% | 100.00% | 0 | 0 | broke: p95 > 200 ms |
| Run 1 | 400 | 345.8 | 345.8 | 3,065.84 | 6,353.35 | 6,888.33 | 0.00% | 100.00% | 3,251 | 0 | broke: p95 > 200 ms, dropped iterations, achieved < 95% of target |
| Run 2 | 50 | 50.0 | 50.0 | 6.03 | 8.19 | 10.45 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 100 | 100.0 | 100.0 | 5.71 | 7.61 | 10.34 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 200 | 200.0 | 200.0 | 5.59 | 9.14 | 14.62 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 400 | 400.0 | 400.0 | 13.65 | 83.67 | 167.65 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 600 | 425.4 | 425.4 | 4,644.24 | 5,620.81 | 5,973.54 | 0.00% | 100.00% | 10,478 | 0 | broke: p95 > 200 ms, dropped iterations, achieved < 95% of target |
| Run 2 | 800 | 426.2 | 426.2 | 4,854.08 | 5,620.68 | 5,961.10 | 0.00% | 100.00% | 22,426 | 0 | broke: p95 > 200 ms, dropped iterations, achieved < 95% of target |

### Race

![Race scenario overlay for throughput, latency, CPU, connection pool, PostgreSQL waits, conflicts, and both runs](loadtest/race.png)

| Run | target it/s | achieved it/s | RPS | p50 ms | p95 ms | p99 ms | errors | checks | dropped | busy 409 | result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Run 1 | 50 | 50.0 | 50.0 | 8.34 | 9.91 | 12.06 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 100 | 100.0 | 100.0 | 7.98 | 10.10 | 13.54 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 200 | 200.0 | 200.0 | 10.02 | 15.52 | 24.60 | 0.00% | 100.00% | 0 | 0 | held |
| Run 1 | 300 | 300.0 | 300.0 | 165.26 | 776.64 | 1,121.14 | 0.00% | 100.00% | 0 | 0 | broke: p95 > 200 ms |
| Run 1 | 400 | 324.4 | 324.4 | 4,225.76 | 8,009.27 | 8,770.90 | 0.00% | 100.00% | 4,540 | 0 | broke: p95 > 200 ms, dropped iterations, achieved < 95% of target |
| Run 2 | 50 | 50.0 | 50.0 | 6.41 | 7.96 | 10.26 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 100 | 100.0 | 100.0 | 5.77 | 7.77 | 10.64 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 200 | 200.0 | 200.0 | 7.19 | 10.90 | 16.32 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 300 | 300.0 | 300.0 | 9.55 | 19.45 | 33.33 | 0.00% | 100.00% | 0 | 0 | held |
| Run 2 | 400 | 397.2 | 397.2 | 739.05 | 1,522.60 | 1,857.84 | 0.00% | 100.00% | 167 | 0 | broke: p95 > 200 ms, dropped iterations |

## First broken steps and observed constraints

The first non-held rate below is the first broken row in `report.py`'s rate-sorted output. A midpoint can therefore appear below the chronological doubling step that triggered it. The telemetry values are averages unless described as a peak. CPU values are percentages of one core. PostgreSQL wait values are mean active backends by wait type. SQL rows are the three highest statements by total time for that step, with whitespace normalized.

### Run 1 account at 1,600 iterations/s

The step broke at 1,600 iterations/s on p95 734.29 ms and 411 dropped iterations. Achieved throughput was 1,593.2 iterations/s, with 0% errors and 100% checks. Java used 129.97%, PostgreSQL 41.35%, and k6 58.43%; iowait was 0.02%. Hikari averaged 1.30 active connections, peaked at 74 pending, and had a 1.03 ms mean acquire time. Tomcat peaked at 70 busy threads and GC pause totaled 393 ms. PostgreSQL active backends averaged 0.08 on CPU, 0.00 in Lock, 0.00 in LWLock, 0.00 in IO, and 0.04 in other waits. There were 0 deadlocks and 0 rollbacks. The observed constraint is application request queuing, supported by the pending Hikari requests and the latency spike; k6 was below its saturation flag and PostgreSQL active wait was low.

| Calls | Total ms | Mean ms | Normalized query snippet |
|---:|---:|---:|---|
| 95,591 | 2,123 | 0.022 | `select ... from account ae1_0 where ae1_0.id=$1` |
| 95,591 | 723 | 0.008 | `BEGIN READ ONLY` |
| 1 | 0 | 0.031 | `select ... from account ae1_0 where ae1_0.email=$1` |

### Run 2 account at 1,600 iterations/s

The step broke at 1,600 iterations/s on p95 706.20 ms and 226 dropped iterations. Achieved throughput was 1,596.2 iterations/s, with 0% errors and 100% checks. Java used 135.06%, PostgreSQL 36.83%, and k6 56.28%; iowait was 0.02%. Hikari averaged 1.94 active connections, peaked at 187 pending, and had a 3.52 ms mean acquire time. Tomcat peaked at 200 busy threads and GC pause totaled 380 ms. PostgreSQL active backends averaged 0.02 on CPU, 0.00 in Lock, 0.00 in LWLock, 0.00 in IO, and 0.09 in other waits. There were 0 deadlocks and 0 rollbacks. The same application queue pattern is stronger in Run 2, with 187 pending Hikari requests and all 200 Tomcat threads busy while k6 remained below its saturation flag.

| Calls | Total ms | Mean ms | Normalized query snippet |
|---:|---:|---:|---|
| 95,774 | 1,847 | 0.019 | `select ... from account ae1_0 where ae1_0.id=$1` |
| 95,774 | 617 | 0.006 | `BEGIN READ ONLY` |
| 1 | 0 | 0.031 | `select ... from account ae1_0 where ae1_0.email=$1` |

### Run 1 browse at 600 iterations/s

The step broke at 600 iterations/s on p95 1,764.66 ms, 10,833 dropped iterations, and achieved 419.5 iterations/s against a target of 600. Errors were 0% and checks were 100%. Java used 136.83%, PostgreSQL 64.12%, and k6 92.37%; iowait was 0.03%. Hikari averaged 3.11 active connections, peaked at 31 pending, and had a 3.17 ms mean acquire time. Tomcat peaked at 43 busy threads and GC pause totaled 345 ms. PostgreSQL active backends averaged 0.26 on CPU, 0.00 in Lock, 0.00 in LWLock, 0.00 in IO, and 0.09 in other waits. There were 0 deadlocks and 0 rollbacks. Because k6 used 92% of its one core, the report flags a generator limit. Java and PostgreSQL were also busy, so this run cannot isolate the service limit from generator pressure.

| Calls | Total ms | Mean ms | Normalized query snippet |
|---:|---:|---:|---|
| 25,168 | 9,674 | 0.384 | `select ... from seat s1_0 where s1_0.auditorium_id=$1 order by s1_0.row_letter,s1_0.seat_number` |
| 25,170 | 5,870 | 0.233 | `select ... from showtime se1_0 where se1_0.starts_at>=$1 and se1_0.starts_at<$2 and se1_0.status<>'CANCELLED' ...` |
| 25,168 | 642 | 0.026 | `select ... from showtime se1_0 where se1_0.id=$1` |

### Run 2 browse at 600 iterations/s

The step broke at 600 iterations/s on p95 1,739.33 ms, 10,161 dropped iterations, and achieved 430.3 iterations/s against a target of 600. Errors were 0% and checks were 100%. Java used 135.07%, PostgreSQL 63.41%, and k6 92.82%; iowait was 0.01%. Hikari averaged 3.08 active connections, peaked at 36 pending, and had a 2.52 ms mean acquire time. Tomcat peaked at 46 busy threads and GC pause totaled 296 ms. PostgreSQL active backends averaged 0.30 on CPU, 0.00 in Lock, 0.00 in LWLock, 0.00 in IO, and 0.08 in other waits. There were 0 deadlocks and 0 rollbacks. k6 again exceeded the 90% one-core flag, so the measured browse ceiling is generator-confounded rather than a claim about maximum service throughput.

| Calls | Total ms | Mean ms | Normalized query snippet |
|---:|---:|---:|---|
| 25,818 | 9,372 | 0.363 | `select ... from seat s1_0 where s1_0.auditorium_id=$1 order by s1_0.row_letter,s1_0.seat_number` |
| 25,820 | 5,756 | 0.223 | `select ... from showtime se1_0 where se1_0.starts_at>=$1 and se1_0.starts_at<$2 and se1_0.status<>'CANCELLED' ...` |
| 25,818 | 587 | 0.023 | `select ... from showtime se1_0 where se1_0.id=$1` |

### Run 1 book at 75 iterations/s

The step broke because it dropped one iteration. p95 was 9.29 ms, errors were 0%, and checks were 100.00%. Java used 39.26%, PostgreSQL 23.28%, and k6 13.66%; iowait was 12.43%. Hikari averaged 0.82 active connections with no pending requests and a 0.21 ms mean acquire time. Tomcat peaked at 4 busy threads and GC pause totaled 31 ms. PostgreSQL active backends averaged 0.05 on CPU, 0.00 in Lock, 0.02 in LWLock, 0.22 in IO, and 0.05 in other waits. There were 0 deadlocks and 0 rollbacks. The low CPU and empty pool queue do not identify a service bottleneck, so the single dropped iteration is a measurement limit rather than evidence of a specific tier giving out.

| Calls | Total ms | Mean ms | Normalized query snippet |
|---:|---:|---:|---|
| 4,512 | 888 | 0.197 | `select ... from seat s1_0 where s1_0.auditorium_id=$1 order by s1_0.row_letter,s1_0.seat_number` |
| 4,500 | 636 | 0.141 | `insert into booking (created_at,customer_id,expires_at,showtime_id,status,id) values ($1,$2,$3,$4,$5,$6)` |
| 4,500 | 494 | 0.110 | `update booking set status = 'CONFIRMED' where id = $1 and status = 'PENDING' and expires_at > $2` |

### Run 2 book at 50 iterations/s

The step broke because it dropped three iterations. p95 was 8.28 ms, errors were 0%, and checks were 99.98%. Java used 34.16%, PostgreSQL 13.15%, and k6 9.23%; iowait was 6.20%. Hikari averaged 0.33 active connections with no pending requests and a 0.16 ms mean acquire time. Tomcat peaked at 5 busy threads and GC pause totaled 29 ms. PostgreSQL active backends averaged 0.05 on CPU, 0.00 in Lock, 0.00 in LWLock, 0.13 in IO, and 0.05 in other waits. There were 0 deadlocks and 1 rollback. No tier was saturated in the captured telemetry, so the run does not provide a service bottleneck diagnosis.

| Calls | Total ms | Mean ms | Normalized query snippet |
|---:|---:|---:|---|
| 3,010 | 550 | 0.183 | `select ... from seat s1_0 where s1_0.auditorium_id=$1 order by s1_0.row_letter,s1_0.seat_number` |
| 2,998 | 390 | 0.130 | `insert into booking (created_at,customer_id,expires_at,showtime_id,status,id) values ($1,$2,$3,$4,$5,$6)` |
| 2,997 | 283 | 0.094 | `update booking set status = 'CONFIRMED' where id = $1 and status = 'PENDING' and expires_at > $2` |

### Run 1 contention at 300 iterations/s

The step broke at 300 iterations/s on p95 453.17 ms. Achieved throughput was 300.0 iterations/s, with 0% errors and 100% checks. Java used 105.63%, PostgreSQL 142.53%, and k6 27.20%; iowait was 0.28%. Hikari averaged 4.21 active connections, peaked at 189 pending, and had a 9.76 ms mean acquire time. Tomcat peaked at 200 busy threads and GC pause totaled 174 ms. PostgreSQL active backends averaged 2.11 on CPU, 0.00 in Lock, 0.00 in LWLock, 0.02 in IO, and 0.23 in other waits. There were 0 deadlocks and 14,401 rollbacks. The application queue and PostgreSQL CPU both rose at the first broken step. The booking status query consumed 95,431 ms over 14,360 calls, so the observed constraint is database and application contention on the held-seat path, not k6 saturation.

| Calls | Total ms | Mean ms | Normalized query snippet |
|---:|---:|---:|---|
| 14,360 | 95,431 | 6.646 | `select s.seat_id as seatId, b.status as status from booking b join booked_seat s on s.booking_id = b.id where b.showtime_id = $1 and (b.status = 'CONFIRMED' or b.expires_at > $2)` |
| 18,002 | 5,888 | 0.327 | `select ... from seat s1_0 where s1_0.auditorium_id=$1 order by s1_0.row_letter,s1_0.seat_number` |
| 14,369 | 2,694 | 0.187 | `insert into booking (created_at,customer_id,expires_at,showtime_id,status,id) values ($1,$2,$3,$4,$5,$6)` |

### Run 2 contention at 600 iterations/s

The step broke at 600 iterations/s on p95 5,620.81 ms, 10,478 dropped iterations, and achieved 425.4 iterations/s against a target of 600. Errors were 0% and checks were 100%. Java used 140.87%, PostgreSQL 126.72%, and k6 42.90%; iowait was 0.18%. Hikari averaged 6.50 active connections, peaked at 189 pending, and had a 97.07 ms mean acquire time. Tomcat peaked at 200 busy threads and GC pause totaled 414 ms. PostgreSQL active backends averaged 1.53 on CPU, 0.00 in Lock, 0.06 in LWLock, 0.00 in IO, and 0.18 in other waits. There were 0 deadlocks and 24,225 rollbacks. The same application and database contention is visible, with a much longer connection acquire time. k6 remained below its saturation flag.

| Calls | Total ms | Mean ms | Normalized query snippet |
|---:|---:|---:|---|
| 24,188 | 68,335 | 2.825 | `select s.seat_id as seatId, b.status as status from booking b join booked_seat s on s.booking_id = b.id where b.showtime_id = $1 and (b.status = 'CONFIRMED' or b.expires_at > $2)` |
| 25,524 | 11,598 | 0.454 | `select ... from seat s1_0 where s1_0.auditorium_id=$1 order by s1_0.row_letter,s1_0.seat_number` |
| 24,197 | 5,414 | 0.224 | `insert into booking (created_at,customer_id,expires_at,showtime_id,status,id) values ($1,$2,$3,$4,$5,$6)` |

### Run 1 race at 300 iterations/s

The step broke at 300 iterations/s on p95 776.64 ms. Achieved throughput was 300.0 iterations/s, with 0% errors and 100% checks. Java used 117.92%, PostgreSQL 170.51%, and k6 28.95%; iowait was 1.78%. Hikari averaged 9.00 active connections, peaked at 190 pending, and had a 43.12 ms mean acquire time. Tomcat peaked at 200 busy threads and GC pause totaled 243 ms. PostgreSQL active backends averaged 2.56 on CPU, 0.58 in Lock, 0.04 in LWLock, 0.23 in IO, and 0.27 in other waits. There were 0 deadlocks and 16,102 rollbacks. High PostgreSQL CPU, lock activity, a full Tomcat pool, and a 43.12 ms connection acquire time support database and application contention while free seats are immediately abandoned.

| Calls | Total ms | Mean ms | Normalized query snippet |
|---:|---:|---:|---|
| 16,158 | 149,275 | 9.238 | `select s.seat_id as seatId, b.status as status from booking b join booked_seat s on s.booking_id = b.id where b.showtime_id = $1 and (b.status = $3 or b.expires_at > $2)` |
| 18,002 | 7,858 | 0.437 | `select ... from seat s1_0 where s1_0.auditorium_id=$1 order by s1_0.row_letter,s1_0.seat_number` |
| 18,001 | 5,016 | 0.279 | `delete from booking b where b.status = $5 and b.expires_at <= $1 and (b.customer_id = $2 or b.id in (...))` |

### Run 2 race at 400 iterations/s

The step broke at 400 iterations/s on p95 1,522.60 ms and 167 dropped iterations. Achieved throughput was 397.2 iterations/s, with 0% errors and 100% checks. Java used 141.92%, PostgreSQL 148.58%, and k6 34.30%; iowait was 0.61%. Hikari averaged 9.50 active connections, peaked at 192 pending, and had an 88.28 ms mean acquire time. Tomcat peaked at 200 busy threads and GC pause totaled 326 ms. PostgreSQL active backends averaged 1.65 on CPU, 0.04 in Lock, 0.04 in LWLock, 0.09 in IO, and 0.13 in other waits. There were 0 deadlocks and 22,876 rollbacks. The full Tomcat pool, high Hikari wait, and PostgreSQL CPU support the same application and database contention pattern as Run 1.

| Calls | Total ms | Mean ms | Normalized query snippet |
|---:|---:|---:|---|
| 22,962 | 51,148 | 2.228 | `select s.seat_id as seatId, b.status as status from booking b join booked_seat s on s.booking_id = b.id where b.showtime_id = $1 and (b.status = 'CONFIRMED' or b.expires_at > $2)` |
| 23,835 | 10,630 | 0.446 | `select ... from seat s1_0 where s1_0.auditorium_id=$1 order by s1_0.row_letter,s1_0.seat_number` |
| 23,834 | 7,308 | 0.307 | `delete from booking b where b.status = 'PENDING' and b.expires_at <= $1 and (b.customer_id = $2 or b.id in (...))` |

The browse results require a generator caveat. At each first broken browse step, k6 used 92.37% and 92.82% of its one core. Those steps show a real latency and drop limit for this shared notebook setup, but they do not prove that the service alone has reached its maximum capacity. The account, contention, and race first broken steps did not hit the k6 90% flag. The two book breaks had low process CPU and no Hikari queue, so their dropped iterations did not identify a tier.

## Run-to-run comparison

For each scenario, the throughput delta is `abs(Run 2 target - Run 1 target) / Run 1 target * 100`. p95 deltas use the same formula at every exact target rate shared by both runs. No values are interpolated. A p95 flag marks an absolute delta above 10%.

| Scenario | Run 1 max held | Run 2 max held | Absolute target delta | Throughput flag |
|---|---:|---:|---:|---|
| account | 1,200 | 1,200 | 0.00% | no |
| browse | 400 | 400 | 0.00% | no |
| book | 50 | none | not comparable | not comparable |
| contention | 200 | 400 | 100.00% | yes |
| race | 200 | 300 | 50.00% | yes |

| Scenario | Shared target | Run 1 p95 ms | Run 2 p95 ms | Absolute delta | >10% flag |
|---|---:|---:|---:|---:|---|
| account | 50 | 3.18 | 2.77 | 12.97% | yes |
| account | 100 | 2.40 | 1.97 | 17.79% | yes |
| account | 200 | 2.03 | 1.68 | 17.25% | yes |
| account | 400 | 2.23 | 1.95 | 12.69% | yes |
| account | 800 | 3.56 | 3.70 | 3.81% | no |
| account | 1,200 | 177.36 | 96.61 | 45.53% | yes |
| account | 1,600 | 734.29 | 706.20 | 3.82% | no |
| browse | 50 | 4.22 | 3.85 | 8.74% | no |
| browse | 100 | 4.93 | 4.62 | 6.43% | no |
| browse | 200 | 9.95 | 8.69 | 12.68% | yes |
| browse | 400 | 131.54 | 128.44 | 2.36% | no |
| browse | 600 | 1,764.66 | 1,739.33 | 1.44% | no |
| browse | 800 | 2,060.38 | 1,920.25 | 6.80% | no |
| book | 50 | 9.89 | 8.28 | 16.25% | yes |
| contention | 50 | 13.02 | 8.19 | 37.09% | yes |
| contention | 100 | 10.24 | 7.61 | 25.62% | yes |
| contention | 200 | 16.63 | 9.14 | 45.04% | yes |
| contention | 400 | 6,353.35 | 83.67 | 98.68% | yes |
| race | 50 | 9.91 | 7.96 | 19.60% | yes |
| race | 100 | 10.10 | 7.77 | 23.00% | yes |
| race | 200 | 15.52 | 10.90 | 29.79% | yes |
| race | 300 | 776.64 | 19.45 | 97.50% | yes |
| race | 400 | 8,009.27 | 1,522.60 | 80.99% | yes |

Contention's highest held target differed by 100%; race's differed by 50%. Account and browse held the same highest target in both runs. Book has no comparable max-held target because Run 2 did not hold its first step. Shared-rate p95 differs by more than 10% in every scenario, at the rates flagged above. These two runs show substantial variation, but do not establish its cause or a statistical confidence interval.

## Contention and race verification

Every measured contention and race step wrote `PASS SQL verification`. The verifier checked that HTTP-created bookings minus abandoned bookings equaled booked-seat rows, that the booked-seat rows had no duplicate seats, and that total 409 conflicts equaled seat conflicts plus pending-customer, busy, and free-again conflicts.

### Contention

| Run | target | 201 created | abandoned | 409 total | seat | pending | busy | free again | booked rows | duplicates | result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Run 1 | 50 | 10 | 0 | 2,991 | 1,845 | 1,146 | 0 | 0 | 10 | 0 | PASS |
| Run 1 | 100 | 10 | 0 | 5,991 | 4,245 | 1,746 | 0 | 0 | 10 | 0 | PASS |
| Run 1 | 200 | 10 | 0 | 11,991 | 9,634 | 2,357 | 0 | 0 | 10 | 0 | PASS |
| Run 1 | 300 | 10 | 0 | 17,991 | 14,359 | 3,632 | 0 | 0 | 10 | 0 | PASS |
| Run 1 | 400 | 10 | 0 | 20,740 | 19,396 | 1,344 | 0 | 0 | 10 | 0 | PASS |
| Run 2 | 50 | 10 | 0 | 2,991 | 1,845 | 1,146 | 0 | 0 | 10 | 0 | PASS |
| Run 2 | 100 | 10 | 0 | 5,991 | 4,244 | 1,747 | 0 | 0 | 10 | 0 | PASS |
| Run 2 | 200 | 10 | 0 | 11,991 | 9,644 | 2,347 | 0 | 0 | 10 | 0 | PASS |
| Run 2 | 400 | 10 | 0 | 23,991 | 20,996 | 2,995 | 0 | 0 | 10 | 0 | PASS |
| Run 2 | 600 | 10 | 0 | 25,513 | 24,187 | 1,326 | 0 | 0 | 10 | 0 | PASS |
| Run 2 | 800 | 10 | 0 | 25,565 | 24,653 | 912 | 0 | 0 | 10 | 0 | PASS |

### Race

| Run | target | 201 created | abandoned | 409 total | seat | pending | busy | free again | booked rows | duplicates | result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Run 1 | 50 | 2,996 | 2,996 | 5 | 5 | 0 | 0 | 0 | 0 | 0 | PASS |
| Run 1 | 100 | 5,946 | 5,946 | 55 | 55 | 0 | 0 | 0 | 0 | 0 | PASS |
| Run 1 | 200 | 6,189 | 6,189 | 5,812 | 5,811 | 0 | 0 | 1 | 0 | 0 | PASS |
| Run 1 | 300 | 1,844 | 1,844 | 16,157 | 15,542 | 0 | 0 | 615 | 0 | 0 | PASS |
| Run 1 | 400 | 206 | 206 | 19,255 | 19,180 | 0 | 0 | 75 | 0 | 0 | PASS |
| Run 2 | 50 | 2,999 | 2,999 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | PASS |
| Run 2 | 100 | 5,979 | 5,979 | 22 | 22 | 0 | 0 | 0 | 0 | 0 | PASS |
| Run 2 | 200 | 8,763 | 8,763 | 3,238 | 3,226 | 0 | 0 | 12 | 0 | 0 | PASS |
| Run 2 | 300 | 7,340 | 7,340 | 10,661 | 10,554 | 0 | 0 | 107 | 0 | 0 | PASS |
| Run 2 | 400 | 873 | 873 | 22,961 | 22,599 | 0 | 0 | 362 | 0 | 0 | PASS |

## Reproduction and limits

To regenerate the overlays, replace the two result-directory placeholders:

```bash
python3 loadtest/charts.py --out docs/loadtest "baseline-1=<run-1-results>" "baseline-2=<run-2-results>"
```

Only the six overlaid PNGs are stored in the repository. Each public notebook's Output tab provides `env.txt`, application and k6 logs, summaries, telemetry, SQL statements, database counters and verification files. Compare later runs using the same protocol and recorded CPU allocation. Browse is limited by the generator in this setup; book's service limit remains unresolved.
