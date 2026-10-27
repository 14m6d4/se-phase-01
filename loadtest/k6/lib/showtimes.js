import http from 'k6/http';
import { fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// The seed creates showtimes for the seven VN-local dates starting the day after
// it runs. Scan days 0..8 from one clock capture, so a VN midnight that passes
// between seeding and this run neither loses a day nor mixes two dates.
export function seededShowtimes() {
	const now = Date.now();
	const showtimes = [];
	for (let day = 0; day <= 8; day++) {
		const date = new Date(now + (7 + day * 24) * 3600 * 1000).toISOString().slice(0, 10);
		const res = http.get(`${BASE_URL}/api/showtimes?date=${date}`);
		if (res.status !== 200) fail(`GET /api/showtimes?date=${date}: HTTP ${res.status} ${res.body}`);
		const daily = res.json();
		if (!Array.isArray(daily)) fail(`GET /api/showtimes?date=${date} returned a non-list`);
		showtimes.push(...daily);
	}
	if (showtimes.length === 0) fail('no seeded showtimes in the nine days around today');
	return showtimes;
}
