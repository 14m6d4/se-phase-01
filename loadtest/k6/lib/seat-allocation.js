export const BOOK_MAX_VUS = 12;
export const BOOK_MIN_SEATS_PER_VU = 4100;

export function partitionSlots(slots, count) {
	return Array.from({ length: count }, (_, i) =>
		slots.slice(Math.floor(i * slots.length / count), Math.floor((i + 1) * slots.length / count)));
}
