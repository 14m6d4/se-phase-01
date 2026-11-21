// Browse scenario: anonymous users browse the public catalogue and seat map.
// No login: both endpoints are public, so no BCrypt is paid anywhere in the run.
import http from 'k6/http';
import { check } from 'k6';
import { LOAD, arrivalRate, handleSummary, summaryOptions } from './lib/summary.js';

export { handleSummary };

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EXPECTED_SEAT_COUNT = 120;

// Discard bodies in Go and fix tag names so the hot path avoids Goja JSON parsing,
// body string allocation, and per-UUID time-series cardinality.
const LIST_PARAMS = { ...LOAD, responseType: 'none', tags: { ...LOAD.tags, name: 'GET /api/showtimes' } };
const SEATS_PARAMS = { ...LOAD, responseType: 'none', tags: { ...LOAD.tags, name: 'GET /api/showtimes/:id/seats' } };
const LIST_CHECK = { 'showtimes status is 200': (r) => r.status === 200 };
const SEATS_CHECK = { 'seats status is 200': (r) => r.status === 200 };

export const options = {
	...summaryOptions,
	scenarios: { browse: arrivalRate() },
};

// Today's date in the Vietnam timezone, as the API expects it (`?date=` is
// interpreted in Asia/Ho_Chi_Minh).
function today() {
	return new Date(Date.now() + 7 * 3600 * 1000).toISOString().slice(0, 10);
}

function tomorrow() {
	return new Date(Date.now() + (7 + 24) * 3600 * 1000).toISOString().slice(0, 10);
}

// Preflight: abort the whole run before any VU starts when nothing is seeded or
// the seat map size does not match the seeded 120-seat layout.
// These requests are untagged (no LOAD), so they stay out of the measured numbers.
export function setup() {
	return preflightShowtimes();
}

function preflightShowtimes() {
	const d = today();
	for (const date of [d, tomorrow()]) {
		const listUrl = `${BASE_URL}/api/showtimes?date=${date}`;
		const r = http.get(listUrl);
		if (r.status !== 200) throw new Error(`GET /api/showtimes: HTTP ${r.status} ${r.body}`);
		const showtimes = r.json();
		if (showtimes.length > 0) {
			const seatUrls = showtimes.map((s) => `${BASE_URL}/api/showtimes/${s.id}/seats`);
			const seats = http.get(seatUrls[0]);
			if (seats.status !== 200) throw new Error(`GET ${seatUrls[0]}: HTTP ${seats.status} ${seats.body}`);
			if (seats.json().length !== EXPECTED_SEAT_COUNT) {
				throw new Error(`expected ${EXPECTED_SEAT_COUNT} seats, got ${seats.json().length}`);
			}
			return { date, listUrl, seatUrls };
		}
	}
	throw new Error('no showtimes seeded for today or tomorrow');
}

export default function ({ listUrl, seatUrls }) {
	// Exactly two measured requests per iteration: one list, one seat map.
	const list = http.get(listUrl, LIST_PARAMS);
	check(list, LIST_CHECK);
	const seats = http.get(seatUrls[(__VU + __ITER) % seatUrls.length], SEATS_PARAMS);
	check(seats, SEATS_CHECK);
}
