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
 * Baseline strategy (ADR 0004): Pure database constraint enforcement via
 * {@code uq_booked_seat_showtime_seat UNIQUE(showtime_id, seat_id)}.
 */
class UniqueClaim implements Claim {

	private final BookingEntityRepository jpa;
	private final TransactionOperations transaction;

	UniqueClaim(BookingEntityRepository jpa, TransactionOperations transaction) {
		this.jpa = jpa;
		this.transaction = transaction;
	}

	@Override
	public HoldOutcome hold(Booking booking, Instant now) {
		List<UUID> seatIds = booking.seats().stream().map(BookedSeat::seatId).toList();
		try {
			return transaction.execute(status -> {
				// First, always (ADR 0005): a cancel then waits for this Booking, or has committed and is seen here.
				if ("CANCELLED".equals(jpa.lockShowtimeStatus(booking.showtimeId()))) {
					status.setRollbackOnly();
					return new HoldOutcome.ShowtimeCancelled();
				}
				jpa.deleteLapsedHolds(booking.customerId(), booking.showtimeId(), seatIds, now);
				return new HoldOutcome.Held(jpa.saveAndFlush(BookingEntity.from(booking)).toBooking());
			});
		} catch (DataIntegrityViolationException e) {
			// The insert that loses the race fails here (ADR 0004).
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
