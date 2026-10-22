// Shared end-of-test summary for every scenario.
// A scenario re-exports handleSummary, spreads summaryOptions into its options
// and passes LOAD as the params of every request it wants measured.
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

// k6 does not create this directory; whoever runs k6 must.
const RESULTS_DIR = __ENV.RESULTS_DIR || 'loadtest/results';

// Tags the measured requests, so setup's logins (BCrypt) stay out of the numbers.
export const LOAD = { tags: { phase: 'load' } };

export const summaryOptions = {
	// k6's default stats stop at p(95); the report needs p(99).
	summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
	thresholds: {
		checks: ['rate==1'],
		dropped_iterations: ['count==0'],
		'http_req_failed{phase:load}': ['rate==0'],
		// k6 needs thresholds to include these tagged sub-metrics in the summary.
		'http_reqs{phase:load}': ['count>=0'],
		'http_req_duration{phase:load}': ['max>=0'],
	},
};

export function handleSummary(data) {
	// setup's return value (the Customers' tokens) has no place in a shared results file.
	delete data.setup_data;
	return {
		stdout: textSummary(data, { indent: ' ', enableColors: false }),
		[`${RESULTS_DIR}/summary.json`]: JSON.stringify(data, null, 2),
	};
}
