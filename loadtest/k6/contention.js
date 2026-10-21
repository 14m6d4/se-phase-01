// Contention scenario: all VUs compete for the same ten Seats on one Showtime.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { LOAD, handleSummary as sharedHandleSummary, summaryOptions } from './lib/summary.js';
import { conflictReason } from './lib/conflicts.js';
import { loginCustomers, MAX_VUS } from './lib/customers.js';
import { seededShowtimes } from './lib/showtimes.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RESULTS_DIR = __ENV.RESULTS_DIR || 'loadtest/results';
const EXPECTED_STATUSES = http.expectedStatuses(201, 409);
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';
const bookingsCreated = new Counter('bookings_created');
const bookingConflicts = new Counter('booking_conflicts');
const seatConflicts = new Counter('seat_conflicts');
const pendingCustomerConflicts = new Counter('pending_customer_conflicts');

export const options = {
	...summaryOptions,
	setupTimeout: '300s',
	thresholds: {
		...summaryOptions.thresholds,
		checks: ['rate==1'],
		'http_req_failed{phase:load}': ['rate==0'],
		bookings_created: ['count>=1'],
		booking_conflicts: ['count>=1'],
		seat_conflicts: ['count>=1'],
	},
	scenarios: {
		contention: {
			executor: 'ramping-arrival-rate',
			startRate: 0,
			timeUnit: '1s',
			preAllocatedVUs: 50,
			maxVUs: MAX_VUS,
			stages: [
				{ duration: '30s', target: RATE },
				{ duration: DURATION, target: RATE },
				{ duration: '10s', target: 0 },
			],
		},
	},
};

export function setup() {
	const showtime = seededShowtimes()[0];
	const res = http.get(`${BASE_URL}/api/showtimes/${showtime.id}/seats`);
	if (res.status !== 200) fail(`GET seats for ${showtime.id}: HTTP ${res.status} ${res.body}`);
	const seats = res.json();
	if (!Array.isArray(seats)) fail(`seat map for ${showtime.id} is not a list`);
	const seatIds = seats.filter((seat) => seat.status === 'AVAILABLE').slice(0, 10).map((seat) => seat.id);
	if (seatIds.length !== 10) fail(`Showtime ${showtime.id} has only ${seatIds.length} available Seats; need 10`);
	return { tokens: loginCustomers(MAX_VUS), showtimeId: showtime.id, seatIds };
}

export function handleSummary(data) {
	const showtimeId = data.setup_data && data.setup_data.showtimeId;
	return {
		...sharedHandleSummary(data),
		[`${RESULTS_DIR}/contention.json`]: JSON.stringify({ showtimeId }, null, 2),
	};
}

function validBooking(res, showtimeId, seatId) {
	if (res.status !== 201) return false;
	try {
		const booking = res.json();
		return typeof booking.id === 'string' && booking.showtimeId === showtimeId
			&& booking.status === 'PENDING' && Array.isArray(booking.seats)
			&& booking.seats.length === 1 && booking.seats[0].seatId === seatId && booking.totalPrice > 0;
	} catch (_) {
		return false;
	}
}

function classifyConflict(res, seatId) {
	let problem = null;
	try {
		problem = res.json();
	} catch (_) {
		// A 409 without a problem body is unexpected and fails the check below.
	}
	return conflictReason(res.status, res.headers['Content-Type'], problem, seatId);
}

export default function ({ tokens, showtimeId, seatIds }) {
	const index = __VU - 1;
	if (index >= tokens.length) fail(`VU ${__VU} has no Customer`);
	const seatId = seatIds[__ITER % seatIds.length];
	const res = http.post(`${BASE_URL}/api/bookings`, JSON.stringify({ showtimeId, seatIds: [seatId] }), {
		...LOAD,
		headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokens[index]}` },
		responseCallback: EXPECTED_STATUSES,
	});
	if (res.status === 201) {
		bookingsCreated.add(1);
		const ok = check(res, { '201 creates a one-seat Pending Booking': (response) =>
			validBooking(response, showtimeId, seatId) });
		if (!ok) fail(`invalid 201 response for Showtime ${showtimeId}, Seat ${seatId}: ${res.body}`);
	} else if (res.status === 409) {
		const reason = classifyConflict(res, seatId);
		const ok = check(res, { '409 is a seat or pending-Customer conflict': () => reason !== null });
		if (!ok) fail(`unexpected 409 reason: ${res.body}`);
		bookingConflicts.add(1);
		if (reason === 'seat') seatConflicts.add(1);
		else pendingCustomerConflicts.add(1);
	} else {
		check(res, { 'response is 201 or expected 409': () => false });
		fail(`unexpected booking response: HTTP ${res.status} ${res.body}`);
	}
}
