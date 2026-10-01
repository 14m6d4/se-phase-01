// Smoke scenario: every VU acts as its own Customer and reads its Account.
import http from 'k6/http';
import { check } from 'k6';
import { LOAD, handleSummary, summaryOptions } from './lib/summary.js';
import { loginCustomers, MAX_VUS } from './lib/customers.js';

export { handleSummary };

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// Target requests per second and how long to hold it; the notebook passes them with -e.
const RATE = Number(__ENV.RATE || 50);
const HOLD = __ENV.HOLD || '60s';

export const options = {
	...summaryOptions,
	setupTimeout: '300s',
	scenarios: {
		me: {
			// Arrival rate: throughput is set by the target, not by how fast responses come back.
			executor: 'ramping-arrival-rate',
			startRate: 0,
			timeUnit: '1s',
			preAllocatedVUs: 50,
			maxVUs: MAX_VUS,
			stages: [
				{ duration: '30s', target: RATE },
				{ duration: HOLD, target: RATE },
				{ duration: '10s', target: 0 },
			],
		},
	},
};

export function setup() {
	return { tokens: loginCustomers(MAX_VUS) };
}

export default function ({ tokens }) {
	const token = tokens[__VU - 1];
	const res = http.get(`${BASE_URL}/api/me`, { ...LOAD, headers: { Authorization: `Bearer ${token}` } });
	check(res, { 'status is 200': (r) => r.status === 200 });
}
