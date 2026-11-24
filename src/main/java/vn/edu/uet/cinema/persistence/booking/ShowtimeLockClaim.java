package vn.edu.uet.cinema.persistence.booking;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.business.showtime.inventory.BookedSeat;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome;
import vn.edu.uet.cinema.config.BookingMetrics;

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
	private final BookingMetrics metrics;
	private final String strategy;

	ShowtimeLockClaim(BookingEntityRepository jpa, TransactionOperations transaction) {
		this(jpa, transaction, BookingMetrics.noop(), "showtime-lock");
	}

	ShowtimeLockClaim(BookingEntityRepository jpa, TransactionOperations transaction, BookingMetrics metrics,
		String strategy) {
		this.jpa = jpa;
		this.transaction = transaction;
		this.metrics = metrics != null ? metrics : BookingMetrics.noop();
		this.strategy = strategy;
	}

	@Override
	public HoldOutcome hold(Booking booking, Instant now) {
		List<UUID> seatIds = booking.seats().stream().map(BookedSeat::seatId).toList();
		long[] bodyEnd = {-1L};
		boolean[] rollbackOnly = {false};
		try {
			HoldOutcome outcome = transaction.execute(status -> {
				// 1. Lock showtime row exclusively (ADR 0011)
				String showtimeStatus = metrics.timeLockQuery("showtime_update", "hold", "lock_showtime", strategy,
					() -> jpa.lockShowtimeStatusForUpdate(booking.showtimeId()));
				if (showtimeStatus == null) {
					bodyEnd[0] = System.nanoTime();
					return new HoldOutcome.ShowtimeGone();
				}
				// 2. Reject if not SCHEDULED (e.g. CANCELLED)
				if (!"SCHEDULED".equals(showtimeStatus)) {
					status.setRollbackOnly();
					rollbackOnly[0] = true;
					return new HoldOutcome.ShowtimeCancelled();
				}

				// 3. Delete lapsed holds on requested seats and customer's own lapsed holds
				metrics.timeDbQuery("hold", "delete_lapsed_holds", strategy,
					() -> jpa.deleteLapsedHolds(booking.customerId(), booking.showtimeId(), seatIds, now));

				Optional<BookingEntity> livePending = metrics.timeDbQuery("hold", "check_pending", strategy,
					() -> jpa.findLivePending(booking.customerId(), now));
				if (livePending.isPresent()) {
					bodyEnd[0] = System.nanoTime();
					return new HoldOutcome.CustomerHasPending(livePending.get().getId());
				}

				// 4. Check taken seats INSIDE the row lock
				List<UUID> taken = metrics.timeDbQuery("hold", "check_taken_seats", strategy,
					() -> jpa.findTakenSeats(booking.showtimeId(), seatIds, now));
				if (!taken.isEmpty()) {
					Set<UUID> takenSet = Set.copyOf(taken);
					// Zero failed inserts, zero database rollbacks: commits lapsed hold cleanup cleanly
					bodyEnd[0] = System.nanoTime();
					return new HoldOutcome.SeatsTaken(seatIds.stream().filter(takenSet::contains).toList());
				}

				// 5. If free, insert booking and return Held
				Booking saved = metrics.timeDbQuery("hold", "persist_booking", strategy,
					() -> jpa.saveAndFlush(BookingEntity.from(booking)).toBooking());
				bodyEnd[0] = System.nanoTime();
				return new HoldOutcome.Held(saved);
			});
			if (bodyEnd[0] > 0 && !rollbackOnly[0]) {
				metrics.recordCommit("hold", strategy, System.nanoTime() - bodyEnd[0]);
			}
			return outcome;
		} catch (DataIntegrityViolationException e) {
			// Permanent last-line-of-defense guard (ADR 0004)
			if (hasConstraint(e, "uq_booking_customer_pending")) {
				UUID pendingId = metrics.timeDbQuery("hold", "conflict_lookup", strategy,
					() -> jpa.findLivePending(booking.customerId(), now).map(BookingEntity::getId).orElse(null));
				return new HoldOutcome.CustomerHasPending(pendingId);
			}
			if (hasConstraint(e, "uq_booked_seat_showtime_seat")) {
				Set<UUID> taken = Set.copyOf(metrics.timeDbQuery("hold", "conflict_lookup", strategy,
					() -> jpa.findTakenSeats(booking.showtimeId(), seatIds, now)));
				return new HoldOutcome.SeatsTaken(seatIds.stream().filter(taken::contains).toList());
			}
			if (hasConstraint(e, "fk_booking_showtime")) {
				return new HoldOutcome.ShowtimeGone();
			}
			throw e;
		}
	}
}
