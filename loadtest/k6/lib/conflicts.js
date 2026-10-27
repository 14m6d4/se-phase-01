// Classifies the two expected booking 409s without importing k6, so Node can test it.
// A seat conflict is decided by the root `seatIds` property, never by the human message.
const PROBLEM_MEDIA_TYPE = 'application/problem+json';
const PENDING_CUSTOMER_PREFIX = 'Customer already has a Pending Booking';

function isProblemJson(contentType) {
	if (typeof contentType !== 'string') return false;
	// Parameters are not the classifier's concern: quotes may contain ';' or '='.
	return contentType.split(';', 1)[0].trim().toLowerCase() === PROBLEM_MEDIA_TYPE;
}

export function conflictReason(status, contentType, problem, seatId) {
	if (status !== 409 || typeof seatId !== 'string' || !isProblemJson(contentType)) return null;
	if (problem === null || typeof problem !== 'object' || problem.status !== 409) return null;
	if ('seatIds' in problem) {
		return Array.isArray(problem.seatIds) && problem.seatIds.includes(seatId) ? 'seat' : null;
	}
	if (typeof problem.detail === 'string' && problem.detail.startsWith(PENDING_CUSTOMER_PREFIX)) {
		return 'pending_customer';
	}
	return null;
}
