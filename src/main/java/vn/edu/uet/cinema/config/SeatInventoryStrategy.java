package vn.edu.uet.cinema.config;

import java.util.Arrays;

/**
 * Concurrency strategies for seat inventory reservation (ADR 0010).
 */
public enum SeatInventoryStrategy {

	UNIQUE("unique"),
	SHOWTIME_LOCK("showtime-lock"),
	SEAT_ROWS("seat-rows"),
	SINGLE_WRITER("single-writer");

	private final String value;

	SeatInventoryStrategy(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static SeatInventoryStrategy from(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException(
				"Seat inventory strategy must not be blank. Allowed values: [unique, showtime-lock, seat-rows, single-writer]");
		}
		String normalized = raw.trim().toLowerCase();
		for (SeatInventoryStrategy strategy : values()) {
			if (strategy.value.equals(normalized)) {
				return strategy;
			}
		}
		throw new IllegalArgumentException("Unknown seat inventory strategy: '" + raw
			+ "'. Allowed values: [unique, showtime-lock, seat-rows, single-writer]");
	}
}
