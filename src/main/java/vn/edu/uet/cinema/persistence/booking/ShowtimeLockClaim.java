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
 * Strategy (a) Showtime row lock (ADR 0011):
 * Serializes seat reservation per showtime using pessimistic row lock
 * {@code SELECT status FROM showtime WHERE id = ? FOR NO KEY UPDATE}.
 * Inspects taken seats inside the lock; rejects with {@link HoldOutcome.SeatsTaken}
 * immediately if any seat is taken, achieving zero failed inserts and zero database rollbacks.
 */
class ShowtimeLockClaim implements Claim {

	private final BookingEntityRepository jpa;
	private final TransactionOperations transaction;

	ShowtimeLockClaim(BookingEntityRepository jpa, TransactionOperations transaction) {
		this.jpa = jpa;
		this.transaction = transaction;
	}

	@Override
	public HoldOutcome hold(Booking booking, Instant now) {
		List<UUID> seatIds = booking.seats().stream().map(BookedSeat::seatId).toList();
		try {
			return transaction.execute(status -> {
				// 1. Lock showtime row exclusively (ADR 0011)
				String showtimeStatus = jpa.lockShowtimeStatusForUpdate(booking.showtimeId());
				if (showtimeStatus == null) {
					return new HoldOutcome.ShowtimeGone();
				}
				// 2. Reject if not SCHEDULED (e.g. CANCELLED)
				if (!"SCHEDULED".equals(showtimeStatus)) {
					status.setRollbackOnly();
					return new HoldOutcome.ShowtimeCancelled();
				}

				// 3. Delete lapsed holds on requested seats and customer's own lapsed holds
				jpa.deleteLapsedHolds(booking.customerId(), booking.showtimeId(), seatIds, now);

				Optional<BookingEntity> livePending = jpa.findLivePending(booking.customerId(), now);
				if (livePending.isPresent()) {
					return new HoldOutcome.CustomerHasPending(livePending.get().toBooking().id());
				}

				// 4. Check taken seats INSIDE the row lock
				List<UUID> taken = jpa.findTakenSeats(booking.showtimeId(), seatIds, now);
				if (!taken.isEmpty()) {
					Set<UUID> takenSet = Set.copyOf(taken);
					// Zero failed inserts, zero database rollbacks: commits lapsed hold cleanup cleanly
					return new HoldOutcome.SeatsTaken(seatIds.stream().filter(takenSet::contains).toList());
				}

				// 5. If free, insert booking and return Held
				return new HoldOutcome.Held(jpa.saveAndFlush(BookingEntity.from(booking)).toBooking());
			});
		} catch (DataIntegrityViolationException e) {
			// Permanent last-line-of-defense guard (ADR 0004)
			if (hasConstraint(e, "uq_booking_customer_pending")) {
				UUID pendingId = jpa.findLivePending(booking.customerId(), now)
					.map(BookingEntity::toBooking).map(Booking::id).orElse(null);
				return new HoldOutcome.CustomerHasPending(pendingId);
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
