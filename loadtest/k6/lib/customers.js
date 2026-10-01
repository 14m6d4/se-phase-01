import http from 'k6/http';
import { fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PASSWORD = 'loadtest-password';
export const MAX_VUS = 200;

// Login to pre-seeded Customers so setup avoids registration's extra BCrypt hash.
export function loginCustomers(count = MAX_VUS) {
	const emails = Array.from({ length: count }, (_, i) => `loadtest-${i + 1}@loadtest.local`);
	const logins = http.batch(emails.map((email) => ['POST', `${BASE_URL}/api/auth/login`,
		JSON.stringify({ email, password: PASSWORD }), { headers: { 'Content-Type': 'application/json' } }]));
	return logins.map((res, i) => {
		if (res.status !== 200) fail(`login ${emails[i]}: HTTP ${res.status} ${res.body}`);
		const token = res.json('accessToken');
		if (typeof token !== 'string' || token.length === 0) fail(`login ${emails[i]} returned no accessToken`);
		return token;
	});
}
