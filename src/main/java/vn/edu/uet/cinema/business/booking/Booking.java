package vn.edu.uet.cinema.business.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Booking(UUID id, UUID customerId, UUID showtimeId, BookingStatus status, Instant createdAt,
	Instant expiresAt, List<BookedSeat> seats) {

	public Booking {
		seats = List.copyOf(seats);
	}

	/**
	 * The stored status, except that a Pending Booking whose Hold has lapsed at {@code now} is Expired. This and
	 * {@link #occupiesSeatsAt} are the live-Hold rule; the persistence adapter's SQL twin must say the same.
	 */
	public BookingStatus statusAt(Instant now) {
		return status == BookingStatus.PENDING && !now.isBefore(expiresAt) ? BookingStatus.EXPIRED : status;
	}

	/**
	 * Whether the Booking keeps its Seats from anyone else at {@code now}: it is Confirmed, Refunded (its Seats were
	 * sold, and the database still refuses them to others) or Pending with a live Hold.
	 */
	public boolean occupiesSeatsAt(Instant now) {
		return status == BookingStatus.CONFIRMED || status == BookingStatus.REFUNDED
			|| statusAt(now) == BookingStatus.PENDING;
	}

	public Booking confirmed() {
		return new Booking(id, customerId, showtimeId, BookingStatus.CONFIRMED, createdAt, expiresAt, seats);
	}

	public long totalPrice() {
		return seats.stream().mapToLong(BookedSeat::price).reduce(0L, Math::addExact);
	}
}
