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
 * Strategy 3 (seat-rows): Per-seat row management with atomic upsert claims (ADR 0012).
 */
class SeatRowsClaim implements Claim {

	private final BookingEntityRepository jpa;
	private final TransactionOperations transaction;
	private final BookingMetrics metrics;
	private final String strategy;

	SeatRowsClaim(BookingEntityRepository jpa, TransactionOperations transaction) {
		this(jpa, transaction, BookingMetrics.noop(), "seat-rows");
	}

	SeatRowsClaim(BookingEntityRepository jpa, TransactionOperations transaction, BookingMetrics metrics, String strategy) {
		this.jpa = jpa;
		this.transaction = transaction;
		this.metrics = metrics != null ? metrics : BookingMetrics.noop();
		this.strategy = strategy;
	}

	@Override
	public HoldOutcome hold(Booking booking, Instant now) {
		List<UUID> seatIds = booking.seats().stream().map(BookedSeat::seatId).toList();
		UUID[] seatIdsArray = seatIds.toArray(new UUID[0]);
		long[] bodyEnd = {-1L};
		boolean[] rollbackOnly = {false};
		try {
			HoldOutcome outcome = transaction.execute(status -> {
				// 1. Share lock showtime row (ADR 0005)
				String showtimeStatus = metrics.timeLockQuery("showtime_share", "hold", "lock_showtime", strategy,
					() -> jpa.lockShowtimeStatus(booking.showtimeId()));
				if ("CANCELLED".equals(showtimeStatus)) {
					status.setRollbackOnly();
					rollbackOnly[0] = true;
					return new HoldOutcome.ShowtimeCancelled();
				}

				// 2. Delete lapsed holds on requested seats and customer's own pending booking
				metrics.timeDbQuery("hold", "delete_lapsed_holds", strategy,
					() -> jpa.deleteLapsedHolds(booking.customerId(), booking.showtimeId(), seatIds, now));

				Optional<BookingEntity> livePending = metrics.timeDbQuery("hold", "check_pending", strategy,
					() -> jpa.findLivePending(booking.customerId(), now));
				if (livePending.isPresent()) {
					bodyEnd[0] = System.nanoTime();
					return new HoldOutcome.CustomerHasPending(livePending.get().getId());
				}

				// 3. Execute atomic upsert claim in ascending seat_id order
				List<UUID> claimed = metrics.timeLockQuery("seat_rows_claim", "hold", "claim_seats", strategy,
					() -> jpa.claimSeats(booking.showtimeId(), seatIdsArray, booking.id()));

				// 4. Verify claimed count matches requested count
				if (claimed.size() < seatIds.size()) {
					status.setRollbackOnly();
					rollbackOnly[0] = true;
					Set<UUID> claimedSet = Set.copyOf(claimed);
					List<UUID> taken = seatIds.stream().filter(id -> !claimedSet.contains(id)).toList();
					return new HoldOutcome.SeatsTaken(taken);
				}

				// 5. Success: persist Booking and BookedSeat entities
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
			// Drift fallback: if seats were booked under another strategy and have no showtime_seat row,
			// the claim succeeds but booked_seat UNIQUE constraint catches the conflict.
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
