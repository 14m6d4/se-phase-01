// Account scenario: every VU acts as its own Customer and reads its Account.
import http from 'k6/http';
import { check, fail } from 'k6';
import { LOAD, MAX_VUS, arrivalRate, handleSummary, summaryOptions } from './lib/summary.js';
import { savedTokens } from './lib/customers.js';

export { handleSummary };

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ME_URL = `${BASE_URL}/api/me`;
const STATUS_CHECK = { 'status is 200': (r) => r.status === 200 };
const tokens = savedTokens();
const maxVUs = Math.min(tokens.length, MAX_VUS);
const defaultArrival = arrivalRate(maxVUs);
const WARM_VUS = Math.min(512, defaultArrival.preAllocatedVUs);
let vuParams;

export const options = {
	...summaryOptions,
	scenarios: {
		account: {
			...defaultArrival,
			// Keep a warm pool of persistent HTTP keep-alive connections so high-rate
			// steps do not burst 1,600+ cold TCP handshakes in the first second.
			preAllocatedVUs: WARM_VUS,
		},
	},
};

export function setup() {
	// Pre-warm JWT verification cache and PostgreSQL shared buffers before the measured step.
	const warmCount = Math.min(maxVUs, Math.max(WARM_VUS, defaultArrival.preAllocatedVUs));
	const batchSize = 200;
	for (let i = 0; i < warmCount; i += batchSize) {
		const requests = tokens.slice(i, Math.min(i + batchSize, warmCount)).map((token) => [
			'GET',
			ME_URL,
			null,
			{ responseType: 'none', headers: { Authorization: `Bearer ${token}` } },
		]);
		const responses = http.batch(requests);
		for (let j = 0; j < responses.length; j += 1) {
			if (responses[j].status !== 200) {
				fail(`setup warmup token ${i + j}: HTTP ${responses[j].status}`);
			}
		}
	}
}

export default function () {
	if (!vuParams) {
		const token = tokens[__VU - 1];
		if (!token) fail(`VU ${__VU} has no Customer`);
		vuParams = {
			...LOAD,
			responseType: 'none',
			headers: { Authorization: `Bearer ${token}` },
		};
	}
	const res = http.get(ME_URL, vuParams);
	check(res, STATUS_CHECK);
}
