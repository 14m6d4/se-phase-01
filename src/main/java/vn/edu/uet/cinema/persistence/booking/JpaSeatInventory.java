package vn.edu.uet.cinema.persistence.booking;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.business.showtime.inventory.AbandonOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;
import vn.edu.uet.cinema.business.showtime.inventory.CancelOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.Cancellation;
import vn.edu.uet.cinema.business.showtime.inventory.DeleteOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.PayOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SeatStatus;
import vn.edu.uet.cinema.config.BookingMetrics;
import vn.edu.uet.cinema.config.SeatInventoryStrategy;

/**
 * Every write to {@code booking} and {@code booked_seat}, and the Showtime writes that go with them, each in one
 * transaction (ADR 0009). The live-Hold rule is {@link HoldSql}'s.
 */
@Repository("jpaSeatInventory")
public class JpaSeatInventory implements SeatInventory {

	private final BookingEntityRepository jpa;
	private final TransactionOperations transaction;
	private final SeatInventoryStrategy strategy;
	private final BookingMetrics metrics;
	private final Claim claim;

	@Autowired
	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction,
		@Value("${app.seat-inventory.strategy:unique}") String strategyValue,
		ObjectProvider<BookingMetrics> metricsProvider) {
		this(jpa, transaction, SeatInventoryStrategy.from(strategyValue),
			metricsProvider != null ? metricsProvider.getIfAvailable(BookingMetrics::noop) : BookingMetrics.noop());
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction, String strategyValue) {
		this(jpa, transaction, SeatInventoryStrategy.from(strategyValue), BookingMetrics.noop());
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction,
		SeatInventoryStrategy strategy) {
		this(jpa, transaction, strategy, BookingMetrics.noop());
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction,
		SeatInventoryStrategy strategy, BookingMetrics metrics) {
		this.jpa = jpa;
		this.transaction = transaction;
		this.strategy = strategy;
		this.metrics = metrics != null ? metrics : BookingMetrics.noop();
		this.claim = switch (strategy) {
			case UNIQUE, SINGLE_WRITER -> new UniqueClaim(jpa, transaction, this.metrics, strategy.value());
			case SHOWTIME_LOCK -> new ShowtimeLockClaim(jpa, transaction, this.metrics, strategy.value());
			case SEAT_ROWS -> new SeatRowsClaim(jpa, transaction, this.metrics, strategy.value());
		};
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction) {
		this(jpa, transaction, SeatInventoryStrategy.UNIQUE, BookingMetrics.noop());
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction, Claim claim) {
		this.jpa = jpa;
		this.transaction = transaction;
		this.strategy = SeatInventoryStrategy.UNIQUE;
		this.metrics = BookingMetrics.noop();
		this.claim = claim;
	}

	public SeatInventoryStrategy strategy() {
		return strategy;
	}

	/** Not {@code @Transactional}: each attempt runs in its own transaction, so a deadlock loser can retry. */
	@Override
	public HoldOutcome hold(Booking pending, Instant now) {
		long start = System.nanoTime();
		HoldOutcome outcome = null;
		try {
			try {
				outcome = claim.hold(pending, now);
			} catch (PessimisticLockingFailureException deadlock) {
				metrics.recordDeadlockRetry(strategy.value(), "retried");
				// Overlapping lapsed-Hold deletes can deadlock even when the two Bookings want different Seats.
				try {
					outcome = claim.hold(pending, now);
				} catch (PessimisticLockingFailureException again) {
					metrics.recordDeadlockRetry(strategy.value(), "exhausted");
					outcome = new HoldOutcome.Busy();
				}
			}
			return outcome;
		} finally {
			metrics.recordOperation("hold", strategy.value(), holdOutcomeTag(outcome), System.nanoTime() - start);
		}
	}

	/**
	 * When the conditional update changes nothing, the row is read again in the same transaction. Each statement
	 * sees what was committed when it started, so the read sees a concurrent payment the update waited for. A single
	 * statement (a data-modifying CTE) would not: its outer select shares the snapshot taken before the wait.
	 */
	@Override
	public PayOutcome pay(UUID customerId, UUID bookingId, Instant now) {
		return executePay(customerId, bookingId, now, null, null);
	}

	@Override
	public PayOutcome pay(UUID customerId, UUID bookingId, Instant now, String idempotencyKey) {
		return executePay(customerId, bookingId, now, idempotencyKey, null);
	}

	@Override
	public PayOutcome pay(Booking booking, Instant now) {
		return executePay(booking.customerId(), booking.id(), now, null, booking);
	}

	private PayOutcome executePay(UUID customerId, UUID bookingId, Instant now, String idempotencyKey,
		Booking preloadedBooking) {
		long start = System.nanoTime();
		long[] bodyEnd = {-1L};
		boolean[] rollbackOnly = {false};
		PayOutcome outcome = null;
		try {
			try {
				outcome = transaction.execute(status -> {
					PayOutcome result;
					if (idempotencyKey != null) {
						Optional<BookingEntityRepository.PaymentTxLookup> existing = metrics.timeDbQuery(
							"pay", "idempotency_lookup", strategy.value(),
							() -> jpa.findPaymentByIdempotencyKey(customerId, idempotencyKey));
						if (existing.isPresent()) {
							BookingEntityRepository.PaymentTxLookup tx = existing.get();
							if (!tx.getBookingId().equals(bookingId)) {
								status.setRollbackOnly();
								rollbackOnly[0] = true;
								return new PayOutcome.IdempotencyKeyConflict();
							}
							if ("REFUNDED".equals(tx.getStatus())) {
								return new PayOutcome.NotPayable(BookingStatus.EXPIRED);
							}
							result = reloadBookingForReplay(customerId, bookingId, now);
							rollbackOnly[0] = status.isRollbackOnly();
							bodyEnd[0] = System.nanoTime();
							return result;
						}
					}

					int updated = metrics.timeDbQuery("pay", "confirm_update", strategy.value(),
						() -> jpa.confirm(bookingId, customerId, now));
					if (updated == 1) {
						Optional<Booking> confirmedOpt = preloadedBooking != null
							? Optional.of(preloadedBooking.confirmed())
							: metrics.timeDbQuery("pay", "reload_booking", strategy.value(),
								() -> jpa.findByIdWithSeats(bookingId)).map(BookingEntity::toBooking);
						if (confirmedOpt.isEmpty()) {
							result = new PayOutcome.Gone();
						} else {
							Booking confirmed = confirmedOpt.get();
							metrics.timeDbQuery("pay", "record_payment_tx", strategy.value(),
								() -> jpa.insertPaymentTransaction(UUID.randomUUID(), confirmed.id(), customerId,
									"PAYMENT", "COMPLETED", confirmed.totalPrice(), idempotencyKey, now));
							result = new PayOutcome.Paid(confirmed);
						}
					} else {
						Optional<Booking> currentOpt = metrics.timeDbQuery("pay", "reload_booking", strategy.value(),
							() -> jpa.findByIdWithSeats(bookingId)).map(BookingEntity::toBooking);
						if (currentOpt.isEmpty() || !currentOpt.get().customerId().equals(customerId)) {
							result = new PayOutcome.Gone();
						} else {
							Booking current = currentOpt.get();
							BookingStatus currentStatus = current.statusAt(now);
							if (currentStatus == BookingStatus.CONFIRMED) {
								result = new PayOutcome.AlreadyPaid(current);
							} else {
								if (currentStatus == BookingStatus.EXPIRED) {
									String lateKey = idempotencyKey != null ? idempotencyKey
										: "late-refund:" + current.id();
									metrics.timeDbQuery("pay", "record_late_refund_tx", strategy.value(),
										() -> jpa.insertLatePaymentRefund(UUID.randomUUID(), current.id(), customerId,
											current.totalPrice(), lateKey, now));
								}
								result = new PayOutcome.NotPayable(currentStatus);
							}
						}
					}
					rollbackOnly[0] = status.isRollbackOnly();
					bodyEnd[0] = System.nanoTime();
					return result;
				});
				if (bodyEnd[0] > 0 && !rollbackOnly[0]) {
					metrics.recordCommit("pay", strategy.value(), System.nanoTime() - bodyEnd[0]);
				}
			} catch (DataIntegrityViolationException e) {
				if (idempotencyKey != null && hasConstraint(e, "uq_payment_tx_customer_idempotency_key")) {
					outcome = resolveIdempotencyRace(customerId, bookingId, idempotencyKey, now);
				} else if (hasConstraint(e, "uq_payment_tx_booking_payment")) {
					outcome = reloadBookingForReplay(customerId, bookingId, now);
				} else {
					throw e;
				}
			}
			return outcome;
		} finally {
			metrics.recordOperation("pay", strategy.value(), payOutcomeTag(outcome), System.nanoTime() - start);
		}
	}

	private PayOutcome resolveIdempotencyRace(UUID customerId, UUID bookingId, String idempotencyKey, Instant now) {
		Optional<BookingEntityRepository.PaymentTxLookup> existing =
			jpa.findPaymentByIdempotencyKey(customerId, idempotencyKey);
		if (existing.isPresent()) {
			BookingEntityRepository.PaymentTxLookup tx = existing.get();
			if (!tx.getBookingId().equals(bookingId)) {
				return new PayOutcome.IdempotencyKeyConflict();
			}
			if ("REFUNDED".equals(tx.getStatus())) {
				return new PayOutcome.NotPayable(BookingStatus.EXPIRED);
			}
		}
		return reloadBookingForReplay(customerId, bookingId, now);
	}

	private PayOutcome reloadBookingForReplay(UUID customerId, UUID bookingId, Instant now) {
		return metrics.timeDbQuery("pay", "reload_booking", strategy.value(),
			() -> jpa.findByIdWithSeats(bookingId)).map(BookingEntity::toBooking)
			.<PayOutcome>map(current -> {
				if (!current.customerId().equals(customerId)) {
					return new PayOutcome.Gone();
				}
				BookingStatus currentStatus = current.statusAt(now);
				return currentStatus == BookingStatus.CONFIRMED ? new PayOutcome.AlreadyPaid(current)
					: new PayOutcome.NotPayable(currentStatus);
			}).orElseGet(PayOutcome.Gone::new);
	}

	/** Like {@link #pay}, the row is read again in the same transaction when nothing was deleted. */
	@Override
	public AbandonOutcome abandon(UUID customerId, UUID bookingId) {
		long start = System.nanoTime();
		long[] bodyEnd = {-1L};
		boolean[] rollbackOnly = {false};
		AbandonOutcome outcome = null;
		try {
			outcome = transaction.execute(status -> {
				AbandonOutcome result;
				int deleted = metrics.timeDbQuery("abandon", "delete_pending", strategy.value(),
					() -> jpa.deletePending(bookingId, customerId));
				if (deleted == 1) {
					result = new AbandonOutcome.Abandoned();
				} else {
					result = metrics.timeDbQuery("abandon", "reload_booking", strategy.value(),
						() -> jpa.findById(bookingId)).map(BookingEntity::toBooking)
						.<AbandonOutcome>map(current -> {
							if (!current.customerId().equals(customerId)) {
								return new AbandonOutcome.Gone();
							}
							return new AbandonOutcome.NotPending(current.status());
						}).orElseGet(AbandonOutcome.Gone::new);
				}
				rollbackOnly[0] = status.isRollbackOnly();
				bodyEnd[0] = System.nanoTime();
				return result;
			});
			if (bodyEnd[0] > 0 && !rollbackOnly[0]) {
				metrics.recordCommit("abandon", strategy.value(), System.nanoTime() - bodyEnd[0]);
			}
			return outcome;
		} finally {
			metrics.recordOperation("abandon", strategy.value(), abandonOutcomeTag(outcome), System.nanoTime() - start);
		}
	}

	@Override
	public AbandonOutcome abandon(Booking booking) {
		return abandon(booking.customerId(), booking.id());
	}

	/** Only rows that pass {@code HoldSql.OCCUPIES} come back: a Pending one is a live Hold, the others were sold. */
	@Override
	public Map<UUID, SeatStatus> statuses(UUID showtimeId, Instant now) {
		return metrics.timeDbQuery("statuses", "find_occupied_seats", strategy.value(),
			() -> jpa.findOccupiedSeats(showtimeId, now)).stream().collect(Collectors.toMap(
			BookingEntityRepository.OccupiedSeat::getSeatId,
			seat -> BookingStatus.valueOf(seat.getStatus()) == BookingStatus.PENDING ? SeatStatus.HELD
				: SeatStatus.SOLD));
	}

	/** The order of ADR 0005; see it before changing anything here. */
	@Override
	@Transactional
	public CancelOutcome cancel(UUID showtimeId, Cancellation cancellation, Instant now) {
		if (jpa.cancelShowtime(showtimeId, cancellation.reason().name(), cancellation.note(), now) == 0) {
			return jpa.existsShowtime(showtimeId) ? new CancelOutcome.NotCancellable() : new CancelOutcome.NotFound();
		}
		// Delete before refunding: a payment that commits in between is then refunded, never left Confirmed.
		jpa.deletePendingBookings(showtimeId);
		jpa.refundConfirmedBookings(showtimeId);
		jpa.insertRefundTransactionsForShowtime(showtimeId, now);
		return new CancelOutcome.Cancelled();
	}

	/** Not {@code @Transactional}: the failed transaction is rolled back before the outcome is returned. */
	@Override
	public DeleteOutcome delete(UUID showtimeId, Instant now) {
		try {
			return transaction.execute(status -> {
				jpa.deleteLapsedHoldsOfShowtime(showtimeId, now);
				int rows = jpa.deleteShowtime(showtimeId);
				if (rows == 0) {
					return new DeleteOutcome.NotFound();
				}
				return new DeleteOutcome.Deleted();
			});
		} catch (DataIntegrityViolationException e) {
			if (hasConstraint(e, "fk_booking_showtime")) {
				return new DeleteOutcome.HasBookings();
			}
			throw e;
		}
	}

	private static String holdOutcomeTag(HoldOutcome outcome) {
		if (outcome == null) {
			return "error";
		}
		return switch (outcome) {
			case HoldOutcome.Held ignored -> "held";
			case HoldOutcome.SeatsTaken ignored -> "seats_taken";
			case HoldOutcome.ShowtimeCancelled ignored -> "showtime_cancelled";
			case HoldOutcome.ShowtimeGone ignored -> "showtime_gone";
			case HoldOutcome.CustomerHasPending ignored -> "customer_has_pending";
			case HoldOutcome.Busy ignored -> "busy";
		};
	}

	private static String payOutcomeTag(PayOutcome outcome) {
		if (outcome == null) {
			return "error";
		}
		return switch (outcome) {
			case PayOutcome.Paid ignored -> "paid";
			case PayOutcome.AlreadyPaid ignored -> "already_paid";
			case PayOutcome.NotPayable ignored -> "not_payable";
			case PayOutcome.IdempotencyKeyConflict ignored -> "idempotency_key_conflict";
			case PayOutcome.Gone ignored -> "gone";
		};
	}

	private static String abandonOutcomeTag(AbandonOutcome outcome) {
		if (outcome == null) {
			return "error";
		}
		return switch (outcome) {
			case AbandonOutcome.Abandoned ignored -> "abandoned";
			case AbandonOutcome.NotPending ignored -> "not_pending";
			case AbandonOutcome.Gone ignored -> "gone";
		};
	}
}
