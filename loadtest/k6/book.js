// Each VU uses its own Customer and disjoint Showtime/Seat slice.
import http from 'k6/http';
import { check, fail } from 'k6';
import { LOAD, handleSummary, summaryOptions } from './lib/summary.js';
import { loginCustomers } from './lib/customers.js';
import { BOOK_MAX_VUS, BOOK_MIN_SEATS_PER_VU, partitionSlots } from './lib/seat-allocation.js';
import { seededShowtimes } from './lib/showtimes.js';

export { handleSummary };

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';

export const options = {
	...summaryOptions,
	setupTimeout: '300s',
	thresholds: {
		...summaryOptions.thresholds,
		checks: ['rate==1'],
		'http_req_failed{phase:load}': ['rate==0'],
	},
	scenarios: {
		book: {
			executor: 'ramping-arrival-rate',
			startRate: 0,
			timeUnit: '1s',
			preAllocatedVUs: BOOK_MAX_VUS,
			maxVUs: BOOK_MAX_VUS,
			stages: [
				{ duration: '30s', target: RATE },
				{ duration: DURATION, target: RATE },
				{ duration: '10s', target: 0 },
			],
		},
	},
};

export function setup() {
	const showtimes = seededShowtimes();
	if (showtimes.length < 2) fail('need at least two seeded Showtimes (one reserved for contention)');
	const tokens = loginCustomers(BOOK_MAX_VUS);
	// Keep the first (earliest) Showtime free for the contention scenario in an "all" run.
	const selected = showtimes.slice(1);
	const maps = http.batch(selected.map((showtime) => ['GET', `${BASE_URL}/api/showtimes/${showtime.id}/seats`]));
	const slots = maps.flatMap((res, i) => {
		if (res.status !== 200) fail(`GET seats for ${selected[i].id}: HTTP ${res.status} ${res.body}`);
		const seats = res.json();
		if (!Array.isArray(seats) || seats.length !== 120) fail(`invalid seat map for ${selected[i].id}`);
		return seats.filter((seat) => seat.status === 'AVAILABLE')
			.map((seat) => ({ showtimeId: selected[i].id, seatId: seat.id }));
	});
	const seatSlices = partitionSlots(slots, BOOK_MAX_VUS);
	const minSeats = Math.min(...seatSlices.map((slice) => slice.length));
	if (minSeats < BOOK_MIN_SEATS_PER_VU) {
		fail(`need ${BOOK_MIN_SEATS_PER_VU} unique Seats per VU for the default 4,000 iterations; found ${minSeats}`);
	}
	return { tokens, seatSlices };
}

function validBooking(res, status, bookingStatus, showtimeId, seatId, bookingId) {
	if (res.status !== status) return false;
	try {
		const booking = res.json();
		return typeof booking.id === 'string' && (!bookingId || booking.id === bookingId)
			&& booking.showtimeId === showtimeId && booking.status === bookingStatus
			&& Array.isArray(booking.seats) && booking.seats.length === 1
			&& booking.seats[0].seatId === seatId && booking.totalPrice > 0;
	} catch (_) {
		return false;
	}
}

export default function ({ tokens, seatSlices }) {
	const index = __VU - 1;
	if (index >= tokens.length || index >= seatSlices.length) fail(`VU ${__VU} has no Customer or Seat slice`);
	const seatSlice = seatSlices[index];
	const hasSeat = check(seatSlice, { 'VU has an unused Seat': (slice) => __ITER < slice.length });
	if (!hasSeat) fail(`VU ${__VU} exhausted its ${seatSlice.length} unique Showtime/Seat slots`);
	const { showtimeId, seatId } = seatSlice[__ITER];
	const headers = { ...JSON_HEADERS, Authorization: `Bearer ${tokens[index]}` };
	const hold = http.post(`${BASE_URL}/api/bookings`, JSON.stringify({ showtimeId, seatIds: [seatId] }),
		{ ...LOAD, headers });
	const held = check(hold, { 'hold creates one Pending Booking': (res) =>
		validBooking(res, 201, 'PENDING', showtimeId, seatId) });
	if (!held) fail(`hold failed for Showtime ${showtimeId}, Seat ${seatId}: HTTP ${hold.status} ${hold.body}`);

	const bookingId = hold.json('id');
	const payment = http.post(`${BASE_URL}/api/bookings/${bookingId}/payment`, '',
		{ ...LOAD, headers: { Authorization: headers.Authorization } });
	const paid = check(payment, { 'payment confirms the held Booking': (res) =>
		validBooking(res, 200, 'CONFIRMED', showtimeId, seatId, bookingId) });
	if (!paid) fail(`payment failed for Booking ${bookingId}: HTTP ${payment.status} ${payment.body}`);
}
