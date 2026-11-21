package vn.edu.uet.cinema.persistence.booking;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.business.showtime.inventory.BookedSeat;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome;

/**
 * Strategy 3 (seat-rows): Per-seat row management with atomic upsert claims (ADR 0012).
 */
class SeatRowsClaim implements Claim {

	private final BookingEntityRepository jpa;
	private final TransactionOperations transaction;

	SeatRowsClaim(BookingEntityRepository jpa, TransactionOperations transaction) {
		this.jpa = jpa;
		this.transaction = transaction;
	}

	@Override
	public HoldOutcome hold(Booking booking, Instant now) {
		List<UUID> seatIds = booking.seats().stream().map(BookedSeat::seatId).toList();
		UUID[] seatIdsArray = seatIds.toArray(new UUID[0]);
		try {
			return transaction.execute(status -> {
				// 1. Share lock showtime row (ADR 0005)
				if ("CANCELLED".equals(jpa.lockShowtimeStatus(booking.showtimeId()))) {
					status.setRollbackOnly();
					return new HoldOutcome.ShowtimeCancelled();
				}

				// 2. Delete lapsed holds on requested seats and customer's own pending booking
				jpa.deleteLapsedHolds(booking.customerId(), booking.showtimeId(), seatIds, now);

				if (jpa.findLivePending(booking.customerId(), now).isPresent()) {
					return new HoldOutcome.CustomerHasPending();
				}

				// 3. Execute atomic upsert claim in ascending seat_id order
				List<UUID> claimed = jpa.claimSeats(booking.showtimeId(), seatIdsArray, booking.id());

				// 4. Verify claimed count matches requested count
				if (claimed.size() < seatIds.size()) {
					status.setRollbackOnly();
					Set<UUID> claimedSet = Set.copyOf(claimed);
					List<UUID> taken = seatIds.stream().filter(id -> !claimedSet.contains(id)).toList();
					return new HoldOutcome.SeatsTaken(taken);
				}

				// 5. Success: persist Booking and BookedSeat entities
				return new HoldOutcome.Held(jpa.saveAndFlush(BookingEntity.from(booking)).toBooking());
			});
		} catch (DataIntegrityViolationException e) {
			// Drift fallback: if seats were booked under another strategy and have no showtime_seat row,
			// the claim succeeds but booked_seat UNIQUE constraint catches the conflict.
			if (hasConstraint(e, "uq_booking_customer_pending")) {
				return new HoldOutcome.CustomerHasPending();
			}
			if (hasConstraint(e, "uq_booked_seat_showtime_seat")) {
				Set<UUID> taken = Set.copyOf(jpa.findTakenSeats(booking.showtimeId(), seatIds, now));
				return new HoldOutcome.SeatsTaken(seatIds.stream().filter(taken::contains).toList());
			}
			if (hasConstraint(e, "fk_booking_showtime")) {
				return new HoldOutcome.ShowtimeGone();
			}
			throw e;
		}
	}
}
