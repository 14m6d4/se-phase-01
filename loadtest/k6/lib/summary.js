// Shared end-of-test summary for every scenario.
// A scenario re-exports handleSummary and spreads summaryOptions into its options.
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

const RESULTS_DIR = __ENV.RESULTS_DIR || 'loadtest/results';

// k6's default stats stop at p(95); the report needs p(99).
export const summaryOptions = {
	summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function handleSummary(data) {
	return {
		stdout: textSummary(data, { indent: ' ', enableColors: false }),
		[`${RESULTS_DIR}/summary.json`]: JSON.stringify(data, null, 2),
	};
}
