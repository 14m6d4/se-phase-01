// Smoke scenario: every VU acts as its own Customer and reads its Account.
import http from 'k6/http';
import { check, fail } from 'k6';
import { LOAD, handleSummary, summaryOptions } from './lib/summary.js';

export { handleSummary };

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CUSTOMERS = 50;
const PASSWORD = 'smoke-password';
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };
// Target requests per second and how long to hold it; the notebook passes them with -e.
const RATE = Number(__ENV.RATE || 50);
const HOLD = __ENV.HOLD || '60s';

export const options = {
	...summaryOptions,
	// Registering and logging in pays BCrypt once per Customer.
	setupTimeout: '120s',
	scenarios: {
		me: {
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

// Registers the smoke Customers (existing ones are kept, so re-runs work) and logs each in once,
// so the measured requests don't pay BCrypt.
export function setup() {
	const emails = Array.from({ length: CUSTOMERS }, (_, i) => `smoke-${i}@loadtest.local`);

	const registrations = http.batch(emails.map((email, i) => ['POST', `${BASE_URL}/api/auth/register`,
		JSON.stringify({ email, password: PASSWORD, fullName: `Smoke Customer ${i}` }), JSON_HEADERS]));
	registrations.forEach((res, i) => {
		if (res.status !== 201 && res.status !== 409) {
			fail(`register ${emails[i]}: HTTP ${res.status} ${res.body}`);
		}
	});

	const logins = http.batch(emails.map((email) => ['POST', `${BASE_URL}/api/auth/login`,
		JSON.stringify({ email, password: PASSWORD }), JSON_HEADERS]));
	return {
		tokens: logins.map((res, i) => {
			if (res.status !== 200) {
				fail(`login ${emails[i]}: HTTP ${res.status} ${res.body}`);
			}
			return res.json('accessToken');
		}),
	};
}

export default function ({ tokens }) {
	const token = tokens[(__VU - 1) % tokens.length];
	const res = http.get(`${BASE_URL}/api/me`, { ...LOAD, headers: { Authorization: `Bearer ${token}` } });
	check(res, { 'status is 200': (r) => r.status === 200 });
}
