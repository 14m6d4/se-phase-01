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
import vn.edu.uet.cinema.config.BookingMetrics;

/**
 * Baseline strategy (ADR 0004): Pure database constraint enforcement via
 * {@code uq_booked_seat_showtime_seat UNIQUE(showtime_id, seat_id)}.
 */
class UniqueClaim implements Claim {

	private final BookingEntityRepository jpa;
	private final TransactionOperations transaction;
	private final BookingMetrics metrics;
	private final String strategy;

	UniqueClaim(BookingEntityRepository jpa, TransactionOperations transaction) {
		this(jpa, transaction, BookingMetrics.noop(), "unique");
	}

	UniqueClaim(BookingEntityRepository jpa, TransactionOperations transaction, BookingMetrics metrics, String strategy) {
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
				// First, always (ADR 0005): a cancel then waits for this Booking, or has committed and is seen here.
				String showtimeStatus = metrics.timeLockQuery("showtime_share", "hold", "lock_showtime", strategy,
					() -> jpa.lockShowtimeStatus(booking.showtimeId()));
				if ("CANCELLED".equals(showtimeStatus)) {
					status.setRollbackOnly();
					rollbackOnly[0] = true;
					return new HoldOutcome.ShowtimeCancelled();
				}
				metrics.timeDbQuery("hold", "delete_lapsed_holds", strategy,
					() -> jpa.deleteLapsedHolds(booking.customerId(), booking.showtimeId(), seatIds, now));
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
			// The insert that loses the race fails here (ADR 0004).
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
