package vn.edu.uet.cinema.persistence.booking;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
	private final Claim claim;

	@Autowired
	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction,
		@Value("${app.seat-inventory.strategy:unique}") String strategyValue) {
		this(jpa, transaction, SeatInventoryStrategy.from(strategyValue));
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction,
		SeatInventoryStrategy strategy) {
		this.jpa = jpa;
		this.transaction = transaction;
		this.strategy = strategy;
		this.claim = switch (strategy) {
			case UNIQUE, SINGLE_WRITER -> new UniqueClaim(jpa, transaction);
			case SHOWTIME_LOCK -> new ShowtimeLockClaim(jpa, transaction);
			case SEAT_ROWS -> new SeatRowsClaim(jpa, transaction);
		};
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction) {
		this(jpa, transaction, SeatInventoryStrategy.UNIQUE);
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction, Claim claim) {
		this.jpa = jpa;
		this.transaction = transaction;
		this.strategy = SeatInventoryStrategy.UNIQUE;
		this.claim = claim;
	}

	public SeatInventoryStrategy strategy() {
		return strategy;
	}

	/** Not {@code @Transactional}: each attempt runs in its own transaction, so a deadlock loser can retry. */
	@Override
	public HoldOutcome hold(Booking pending, Instant now) {
		try {
			return claim.hold(pending, now);
		} catch (PessimisticLockingFailureException deadlock) {
			// Overlapping lapsed-Hold deletes can deadlock even when the two Bookings want different Seats.
			try {
				return claim.hold(pending, now);
			} catch (PessimisticLockingFailureException again) {
				return new HoldOutcome.Busy();
			}
		}
	}

	/**
	 * When the conditional update changes nothing, the row is read again in the same transaction. Each statement
	 * sees what was committed when it started, so the read sees a concurrent payment the update waited for. A single
	 * statement (a data-modifying CTE) would not: its outer select shares the snapshot taken before the wait.
	 */
	@Override
	@Transactional
	public PayOutcome pay(UUID customerId, UUID bookingId, Instant now) {
		if (jpa.confirm(bookingId, customerId, now) == 1) {
			return jpa.findById(bookingId).map(BookingEntity::toBooking).<PayOutcome>map(PayOutcome.Paid::new)
				.orElseGet(PayOutcome.Gone::new);
		}
		return jpa.findById(bookingId).map(BookingEntity::toBooking).<PayOutcome>map(current -> {
			if (!current.customerId().equals(customerId)) {
				return new PayOutcome.Gone();
			}
			BookingStatus status = current.statusAt(now);
			return status == BookingStatus.CONFIRMED ? new PayOutcome.AlreadyPaid(current)
				: new PayOutcome.NotPayable(status);
		}).orElseGet(PayOutcome.Gone::new);
	}

	@Override
	@Transactional
	public PayOutcome pay(Booking booking, Instant now) {
		if (jpa.confirm(booking.id(), booking.customerId(), now) == 1) {
			return new PayOutcome.Paid(booking.confirmed());
		}
		return jpa.findById(booking.id()).map(BookingEntity::toBooking).<PayOutcome>map(current -> {
			if (!current.customerId().equals(booking.customerId())) {
				return new PayOutcome.Gone();
			}
			BookingStatus status = current.statusAt(now);
			return status == BookingStatus.CONFIRMED ? new PayOutcome.AlreadyPaid(current)
				: new PayOutcome.NotPayable(status);
		}).orElseGet(PayOutcome.Gone::new);
	}

	/** Like {@link #pay}, the row is read again in the same transaction when nothing was deleted. */
	@Override
	@Transactional
	public AbandonOutcome abandon(UUID customerId, UUID bookingId) {
		if (jpa.deletePending(bookingId, customerId) == 1) {
			return new AbandonOutcome.Abandoned();
		}
		return jpa.findById(bookingId).map(BookingEntity::toBooking).<AbandonOutcome>map(current -> {
			if (!current.customerId().equals(customerId)) {
				return new AbandonOutcome.Gone();
			}
			return new AbandonOutcome.NotPending(current.status());
		}).orElseGet(AbandonOutcome.Gone::new);
	}

	@Override
	@Transactional
	public AbandonOutcome abandon(Booking booking) {
		return abandon(booking.customerId(), booking.id());
	}

	/** Only rows that pass {@code HoldSql.OCCUPIES} come back: a Pending one is a live Hold, the others were sold. */
	@Override
	public Map<UUID, SeatStatus> statuses(UUID showtimeId, Instant now) {
		return jpa.findOccupiedSeats(showtimeId, now).stream().collect(Collectors.toMap(
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
}
