package vn.edu.uet.cinema.business.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Booking(UUID id, UUID customerId, UUID showtimeId, BookingStatus status, Instant createdAt,
	Instant expiresAt, List<BookedSeat> seats) {

	public Booking {
		seats = List.copyOf(seats);
	}

	public long totalPrice() {
		return seats.stream().mapToLong(BookedSeat::price).reduce(0L, Math::addExact);
	}
}
