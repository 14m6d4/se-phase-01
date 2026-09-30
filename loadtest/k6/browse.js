// Browse scenario: anonymous Visitors browse the public catalogue and seat map.
// No login: both endpoints are public, so no BCrypt is paid anywhere in the run.
import http from 'k6/http';
import { check, fail } from 'k6';
import { LOAD, handleSummary, summaryOptions } from './lib/summary.js';

export { handleSummary };

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// Target requests per second and how long to hold it; the notebook passes them with -e.
const RATE = Number(__ENV.RATE || 50);
const HOLD = __ENV.HOLD || '60s';

export const options = {
	...summaryOptions,
	scenarios: {
		browse: {
			// Arrival rate: throughput is set by the target, not by how fast responses come back.
			executor: 'ramping-arrival-rate',
			startRate: 0,
			timeUnit: '1s',
			preAllocatedVUs: 50,
			maxVUs: 200,
			stages: [
				{ duration: '30s', target: RATE },
				{ duration: HOLD, target: RATE },
				{ duration: '10s', target: 0 },
			],
		},
	},
};

// Today's date in the Vietnam timezone, as the API expects it (`?date=` is
// interpreted in Asia/Ho_Chi_Minh).
function today() {
	return new Date(Date.now() + 7 * 3600 * 1000).toISOString().slice(0, 10);
}

function tomorrow() {
	return new Date(Date.now() + (7 + 24) * 3600 * 1000).toISOString().slice(0, 10);
}

// Preflight: abort the whole run before any VU starts when nothing is seeded.
// A `fail()` inside an iteration only logs a GoError but still exits 0, so the
// empty-seed check must happen in setup(), where a thrown error exits 107.
// This request is untagged (no LOAD), so it stays out of the measured numbers.
export function setup() {
	preflightShowtimes();
	return {};
}

function preflightShowtimes() {
	const d = today();
	for (const date of [d, tomorrow()]) {
		const r = http.get(`${BASE_URL}/api/showtimes?date=${date}`);
		if (r.status !== 200) throw new Error(`GET /api/showtimes: HTTP ${r.status} ${r.body}`);
		if (r.json().length > 0) return;
	}
	throw new Error('no showtimes seeded for today or tomorrow');
}

export default function () {
	// Seed data starts on tomorrow's VN date (it must be strictly future at seed
	// time), so fall back to tomorrow when today has no showtimes.
	let date = today();
	let list = http.get(`${BASE_URL}/api/showtimes?date=${date}`, LOAD);
	const okList = check(list, { 'showtimes status is 200': (r) => r.status === 200 });
	if (!okList) fail(`GET /api/showtimes: HTTP ${list.status} ${list.body}`);
	if (list.json().length === 0) {
		date = tomorrow();
		list = http.get(`${BASE_URL}/api/showtimes?date=${date}`, LOAD);
		check(list, { 'showtimes (tomorrow) status is 200': (r) => r.status === 200 });
		if (list.status !== 200) fail(`GET /api/showtimes: HTTP ${list.status} ${list.body}`);
	}
	if (list.json().length === 0) fail('no showtimes seeded for today or tomorrow');

	// Each iteration opens one (deterministically spread) showtime's seat map.
	const showtimes = list.json();
	const showtime = showtimes[(__VU + __ITER) % showtimes.length];
	const seats = http.get(`${BASE_URL}/api/showtimes/${showtime.id}/seats`, LOAD);
	check(seats, {
		'seats status is 200': (r) => r.status === 200,
		'seat map is complete': (r) => r.status === 200 && r.json().length === 120,
	});
}
