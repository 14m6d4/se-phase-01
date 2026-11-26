"""Locust versions of the k6 scenarios in loadtest/k6, for quick local runs before a Kaggle run.

One user class per scenario. Each user is one seeded Customer and runs one iteration per second
(constant_throughput), so `--users N` targets N iterations/s, like k6's constant arrival rate.

  locust -f loadtest/locust/locustfile.py --headless -u 100 -r 100 -t 60s Browse

Environment: BASE_URL (default http://localhost:8080), STEP (contention/race: which reserved Showtime,
0..19), SLOT_OFFSET (book: Showtime/Seat pairs already used), RESULTS (JSON file written at the end),
TOKENS_DIR (cache of Customer tokens, default loadtest/results).

Requests whose name starts with "~" are not measured (k6 excludes them too: setup, the abandon after a
race win). Setup requests go through urllib and never reach Locust's statistics.
"""
import itertools
import json
import os
import time
import urllib.request
from datetime import datetime, timedelta, timezone

import gevent.pool
from locust import FastHttpUser, constant_throughput, events, task

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")
PASSWORD = "loadtest-password"
SEATS_PER_SHOWTIME = 120
SEED_DAYS = 75
STEP_SHOWTIMES = 20
RESERVED_SHOWTIMES = 2 * STEP_SHOWTIMES
VIETNAM = timezone(timedelta(hours=7))
TOKEN_TTL_SECONDS = 10 * 60  # access tokens last 15 minutes; log in again before that

# Customers per scenario, as in loadtest/README.md, so scenarios never share a Customer.
FIRST_CUSTOMER = {"Account": 1, "Book": 1, "Contention": 2001, "Race": 4001}

DATA = {}
COUNTERS = {}
LATENCIES = []
ITER_TIMES = []
USER_IDS = itertools.count()
SLOTS = itertools.count(int(os.environ.get("SLOT_OFFSET", "0")))


def get_json(path, token=None):
    request = urllib.request.Request(BASE_URL + path, headers={"Authorization": f"Bearer {token}"} if token else {})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def login(email):
    body = json.dumps({"email": email, "password": PASSWORD}).encode()
    request = urllib.request.Request(BASE_URL + "/api/auth/login", data=body,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        token = json.load(response).get("accessToken")
    if not token:
        raise RuntimeError(f"login {email} returned no accessToken")
    return token


def tokens(first, count):
    """Tokens for Customers first..first+count-1, cached on disk while they are still valid."""
    directory = os.environ.get("TOKENS_DIR", "loadtest/results")
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, f"locust-tokens-{first}-{count}.json")
    if os.path.exists(path) and time.time() - os.path.getmtime(path) < TOKEN_TTL_SECONDS:
        cached = json.load(open(path))
        try:  # a reset database recreates the Customers, which invalidates every cached token
            get_json("/api/me", cached[0])
            return cached
        except Exception:
            pass
    emails = [f"loadtest-{first + i}@loadtest.local" for i in range(count)]
    result = gevent.pool.Pool(50).map(login, emails)
    json.dump(result, open(path, "w"))
    return result


def day(offset):
    return (datetime.now(VIETNAM) + timedelta(days=offset)).date().isoformat()


def seeded_showtimes():
    showtimes = []
    for offset in range(SEED_DAYS + 2):
        showtimes.extend(get_json(f"/api/showtimes?date={day(offset)}"))
    if not showtimes:
        raise RuntimeError("no seeded Showtimes; run loadtest/seed.sql on a fresh database")
    return showtimes


def available_seats(showtime_id, count):
    seats = [s["id"] for s in get_json(f"/api/showtimes/{showtime_id}/seats") if s["status"] == "AVAILABLE"]
    if len(seats) < count:
        raise RuntimeError(f"Showtime {showtime_id} has only {len(seats)} available Seats; need {count}")
    return seats[:count]


@events.test_start.add_listener
def setup(environment, **_):
    names = {cls.__name__ for cls in environment.user_classes}
    if len(names) != 1:
        raise RuntimeError(f"pick exactly one scenario class, got {sorted(names)}")
    name = names.pop()
    DATA["scenario"] = name
    customers = int(os.environ.get("CUSTOMERS", "2000"))
    if name in FIRST_CUSTOMER:
        DATA["tokens"] = tokens(FIRST_CUSTOMER[name], customers)
    if name == "Browse":
        DATA["date"] = next(d for d in (day(0), day(1)) if get_json(f"/api/showtimes?date={d}"))
    elif name == "Book":
        showtimes = seeded_showtimes()[RESERVED_SHOWTIMES:]
        DATA["book_showtimes"] = showtimes
        firsts = {s["auditoriumId"]: s["id"] for s in reversed(showtimes)}
        DATA["seats_by_auditorium"] = {a: [s["id"] for s in get_json(f"/api/showtimes/{sid}/seats")]
                                       for a, sid in firsts.items()}
    elif name in ("Contention", "Race"):
        step = int(os.environ.get("STEP", "0"))
        if not 0 <= step < STEP_SHOWTIMES:
            raise RuntimeError(f"STEP must be 0..{STEP_SHOWTIMES - 1}")
        offset = 0 if name == "Contention" else STEP_SHOWTIMES
        showtime = seeded_showtimes()[offset + step]
        DATA["showtime_id"] = showtime["id"]
        DATA["seat_ids"] = available_seats(showtime["id"], 10)
    DATA["started"] = time.time()


@events.spawning_complete.add_listener
def spawned(**_):
    DATA["spawned"] = time.time()


@events.request.add_listener
def measure(name, response_time, exception, **_):
    if not name.startswith("~"):
        LATENCIES.append((time.time(), response_time, exception is not None))


def count(key):
    COUNTERS[key] = COUNTERS.get(key, 0) + 1


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p / 100 * (len(ordered) - 1))))]


@events.test_stop.add_listener
def write_results(environment, **_):
    # Judge the steady part only: from 2 s after every user has spawned to 1 s before the stop,
    # so spawning and the cut-off at the end do not count as missed iterations.
    stop = time.time()
    start = DATA.get("spawned", DATA.get("started", stop)) + 2
    end = stop - 1
    window = [(t, ms, failed) for t, ms, failed in LATENCIES if start <= t <= end]
    done = [t for t in ITER_TIMES if start <= t <= end]
    times = [ms for _, ms, _ in window]
    seconds = max(end - start, 1e-9)
    result = {
        "scenario": DATA.get("scenario"),
        "users": environment.runner.user_count if environment.runner else None,
        "window_s": round(seconds, 1),
        "iterations": len(ITER_TIMES),
        "steady_it_s": round(len(done) / seconds, 1),
        "requests": len(window),
        "failures": sum(1 for _, _, failed in window if failed),
        "p50_ms": percentile(times, 50), "p95_ms": percentile(times, 95), "p99_ms": percentile(times, 99),
        "counters": COUNTERS,
        "showtime_id": DATA.get("showtime_id"),
    }
    path = os.environ.get("RESULTS")
    if path:
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        json.dump(result, open(path, "w"), indent=2)
    print(json.dumps(result))


def problem_reason(response, seat_id):
    """The known 409 reasons, as in loadtest/k6/lib/conflicts.js; None for anything else."""
    if response.status_code != 409 or "problem+json" not in (response.headers.get("Content-Type") or ""):
        return None
    try:
        problem = response.json()
    except ValueError:
        return None
    if "seatIds" in problem:
        if not problem["seatIds"]:
            return "free_again"
        return "seat" if seat_id in problem["seatIds"] else None
    detail = problem.get("detail") or ""
    if detail.startswith("Customer already has a Pending Booking"):
        return "pending_customer"
    if detail.startswith("Seats are being booked by others right now"):
        return "busy"
    return None


def valid_booking(booking, status, showtime_id, seat_id, booking_id=None):
    seats = booking.get("seats") or []
    return (booking.get("status") == status and booking.get("showtimeId") == showtime_id
            and (booking_id is None or booking.get("id") == booking_id)
            and len(seats) == 1 and seats[0].get("seatId") == seat_id and booking.get("totalPrice", 0) > 0)


class Scenario(FastHttpUser):
    abstract = True
    host = BASE_URL
    wait_time = constant_throughput(1)

    def on_start(self):
        self.index = next(USER_IDS)
        self.iteration = 0
        if "tokens" in DATA:
            if self.index >= len(DATA["tokens"]):
                raise RuntimeError(f"user {self.index} has no Customer; raise CUSTOMERS")
            self.auth = {"Authorization": f"Bearer {DATA['tokens'][self.index]}"}

    def done(self):
        self.iteration += 1
        ITER_TIMES.append(time.time())


class Account(Scenario):
    @task
    def account(self):
        with self.client.get("/api/me", headers=self.auth, name="GET /api/me", catch_response=True) as r:
            if r.status_code != 200:
                r.failure(f"HTTP {r.status_code}")
        self.done()


class Browse(Scenario):
    @task
    def browse(self):
        with self.client.get(f"/api/showtimes?date={DATA['date']}", name="GET /api/showtimes",
                             catch_response=True) as r:
            if r.status_code != 200 or not r.json():
                r.failure(f"HTTP {r.status_code} or no Showtimes")
                return
            showtimes = r.json()
        showtime = showtimes[(self.index + self.iteration) % len(showtimes)]
        with self.client.get(f"/api/showtimes/{showtime['id']}/seats", name="GET /api/showtimes/{id}/seats",
                             catch_response=True) as r:
            if r.status_code != 200 or len(r.json()) != SEATS_PER_SHOWTIME:
                r.failure("incomplete seat map")
        self.done()


class Book(Scenario):
    @task
    def book(self):
        slot = next(SLOTS)
        count("slots")
        showtimes = DATA["book_showtimes"]
        if slot // SEATS_PER_SHOWTIME >= len(showtimes):
            count("seats_exhausted")
            return
        showtime = showtimes[slot // SEATS_PER_SHOWTIME]
        seat_id = DATA["seats_by_auditorium"][showtime["auditoriumId"]][slot % SEATS_PER_SHOWTIME]
        body = {"showtimeId": showtime["id"], "seatIds": [seat_id]}
        with self.client.post("/api/bookings", json=body, headers=self.auth, name="POST /api/bookings",
                              catch_response=True) as r:
            if r.status_code == 409 and problem_reason(r, seat_id) == "busy":
                count("busy_conflicts")
                r.success()
                self.done()
                return
            if r.status_code != 201 or not valid_booking(r.json(), "PENDING", showtime["id"], seat_id):
                r.failure(f"hold: HTTP {r.status_code}")
                return
            booking_id = r.json()["id"]
        with self.client.post(f"/api/bookings/{booking_id}/payment", headers=self.auth,
                              name="POST /api/bookings/{id}/payment", catch_response=True) as r:
            if r.status_code != 200 or not valid_booking(r.json(), "CONFIRMED", showtime["id"], seat_id, booking_id):
                r.failure(f"payment: HTTP {r.status_code}")
        self.done()


class Contention(Scenario):
    """Customers try to hold one of the same 10 Seats; winners keep their Hold."""
    abandon = False

    @task
    def attempt(self):
        seat_id = DATA["seat_ids"][self.iteration % len(DATA["seat_ids"])]
        body = {"showtimeId": DATA["showtime_id"], "seatIds": [seat_id]}
        with self.client.post("/api/bookings", json=body, headers=self.auth, name="POST /api/bookings",
                              catch_response=True) as r:
            if r.status_code == 201:
                if not valid_booking(r.json(), "PENDING", DATA["showtime_id"], seat_id):
                    r.failure("invalid 201 body")
                    return
                count("bookings_created")
                booking_id = r.json()["id"]
            elif r.status_code == 409:
                reason = problem_reason(r, seat_id)
                if reason is None:
                    r.failure("unexpected 409 reason")
                    return
                r.success()
                count(f"conflict_{reason}")
                booking_id = None
            else:
                r.failure(f"HTTP {r.status_code}")
                return
        if booking_id and self.abandon:
            with self.client.delete(f"/api/bookings/{booking_id}", headers=self.auth, name="~abandon",
                                    catch_response=True) as r:
                if r.status_code == 204:
                    count("bookings_abandoned")
                else:
                    r.failure(f"abandon: HTTP {r.status_code}")
        self.done()


class Race(Contention):
    """Like Contention, but every winner abandons at once, so attempts race for free Seats."""
    abandon = True
