package vn.edu.uet.cinema.persistence.booking;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.business.showtime.inventory.AbandonOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.BookedSeat;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;
import vn.edu.uet.cinema.business.showtime.inventory.CancelOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.Cancellation;
import vn.edu.uet.cinema.business.showtime.inventory.DeleteOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.PayOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SeatStatus;

/**
 * Every write to {@code booking} and {@code booked_seat}, and the Showtime writes that go with them, each in one
 * transaction (ADR 0009). The live-Hold rule is {@link HoldSql}'s.
 */
@Repository
class JpaSeatInventory implements SeatInventory {

	private final BookingEntityRepository jpa;
	private final TransactionOperations transaction;

	JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction) {
		this.jpa = jpa;
		this.transaction = transaction;
	}

	/** Not {@code @Transactional}: each attempt runs in its own transaction, so a deadlock loser can retry. */
	@Override
	public HoldOutcome hold(Booking pending, Instant now) {
		try {
			return insert(pending, now);
		} catch (PessimisticLockingFailureException deadlock) {
			// Overlapping lapsed-Hold deletes can deadlock even when the two Bookings want different Seats.
			try {
				return insert(pending, now);
			} catch (PessimisticLockingFailureException again) {
				return new HoldOutcome.Busy();
			}
		}
	}

	private HoldOutcome insert(Booking booking, Instant now) {
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
			if (hasConstraint(e, "uq_booked_seat_showtime_seat")) {
				// Rolled back by now, so the winner's Seats are visible to a fresh read. If the winner was abandoned
				// in between, the list is empty: the Seats are free again and the client can simply retry.
				// Only the requested Seats are read: this runs once per lost race, so it must not scan the Showtime.
				Set<UUID> taken = Set.copyOf(jpa.findTakenSeats(booking.showtimeId(), seatIds, now));
				return new HoldOutcome.SeatsTaken(seatIds.stream().filter(taken::contains).toList());
			}
			if (hasConstraint(e, "uq_booking_customer_pending")) {
				return new HoldOutcome.CustomerHasPending();
			}
			if (hasConstraint(e, "fk_booking_showtime")) {
				return new HoldOutcome.ShowtimeGone();
			}
			throw e;
		}
	}

	/**
	 * When the conditional update changes nothing, the row is read again in the same transaction. Each statement
	 * sees what was committed when it started, so the read sees a concurrent payment the update waited for. A single
	 * statement (a data-modifying CTE) would not: its outer select shares the snapshot taken before the wait.
	 */
	@Override
	@Transactional
	public PayOutcome pay(Booking booking, Instant now) {
		if (jpa.confirm(booking.id(), now) == 1) {
			return new PayOutcome.Paid(booking.confirmed());
		}
		return jpa.findById(booking.id()).map(BookingEntity::toBooking).<PayOutcome>map(current -> {
			BookingStatus status = current.statusAt(now);
			return status == BookingStatus.CONFIRMED ? new PayOutcome.AlreadyPaid(current)
				: new PayOutcome.NotPayable(status);
		}).orElseGet(PayOutcome.Gone::new);
	}

	/** Like {@link #pay}, the row is read again in the same transaction when nothing was deleted. */
	@Override
	@Transactional
	public AbandonOutcome abandon(Booking booking) {
		if (jpa.deletePending(booking.id()) == 1) {
			return new AbandonOutcome.Abandoned();
		}
		return jpa.findById(booking.id()).<AbandonOutcome>map(current -> new AbandonOutcome.NotPending(
			current.toBooking().status())).orElseGet(AbandonOutcome.Gone::new);
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
			return new CancelOutcome.NotCancellable();
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
				jpa.deleteShowtime(showtimeId);
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
