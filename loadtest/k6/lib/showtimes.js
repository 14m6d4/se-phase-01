import http from 'k6/http';
import { fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// The seed creates showtimes for the seven VN-local dates starting tomorrow.
export function seededShowtimes() {
	const showtimes = [];
	for (let day = 1; day <= 7; day++) {
		const date = new Date(Date.now() + (7 + day * 24) * 3600 * 1000).toISOString().slice(0, 10);
		const res = http.get(`${BASE_URL}/api/showtimes?date=${date}`);
		if (res.status !== 200) fail(`GET /api/showtimes?date=${date}: HTTP ${res.status} ${res.body}`);
		const daily = res.json();
		if (!Array.isArray(daily)) fail(`GET /api/showtimes?date=${date} returned a non-list`);
		showtimes.push(...daily);
	}
	if (showtimes.length === 0) fail('no seeded showtimes for the next seven days');
	return showtimes;
}
