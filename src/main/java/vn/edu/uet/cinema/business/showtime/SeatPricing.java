package vn.edu.uet.cinema.business.showtime;

import vn.edu.uet.cinema.business.auditorium.SeatType;

public final class SeatPricing {

	/**
	 * The highest base price a Showtime accepts, in VND. Well above any real ticket, and low enough that the largest
	 * Booking cannot overflow: 8 Couple Seats cost 8 * (2 * 100,000,000 + 10,000), about 1.6e9.
	 */
	public static final long MAX_BASE_PRICE = 100_000_000L;

	private SeatPricing() {
	}

	public static long price(SeatType type, long basePrice) {
		return switch (type) {
			case STANDARD -> basePrice;
			case VIP -> Math.addExact(basePrice, 20_000L);
			case COUPLE -> Math.addExact(Math.multiplyExact(basePrice, 2L), 10_000L);
		};
	}
}
