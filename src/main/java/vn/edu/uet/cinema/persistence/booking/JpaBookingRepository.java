package vn.edu.uet.cinema.persistence.booking;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.business.booking.AbandonOutcome;
import vn.edu.uet.cinema.business.booking.BookedSeat;
import vn.edu.uet.cinema.business.booking.Booking;
import vn.edu.uet.cinema.business.booking.BookingRepository;
import vn.edu.uet.cinema.business.booking.BookingStatus;
import vn.edu.uet.cinema.business.booking.HoldOutcome;
import vn.edu.uet.cinema.business.booking.PayOutcome;

@Repository
class JpaBookingRepository implements BookingRepository {

	private final BookingEntityRepository jpa;
	private final TransactionOperations transaction;

	JpaBookingRepository(BookingEntityRepository jpa, TransactionOperations transaction) {
		this.jpa = jpa;
		this.transaction = transaction;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Booking> findById(UUID id) {
		return jpa.findById(id).map(BookingEntity::toBooking);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Booking> findByCustomer(UUID customerId) {
		return jpa.findByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(BookingEntity::toBooking).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Booking> findLivePending(UUID customerId, Instant now) {
		return jpa.findLivePending(customerId, now).map(BookingEntity::toBooking);
	}

	/** Not {@code @Transactional}: each attempt runs in its own transaction, so a deadlock loser can retry. */
	@Override
	public HoldOutcome saveNew(Booking booking, Instant now) {
		try {
			return insert(booking, now);
		} catch (PessimisticLockingFailureException deadlock) {
			// Overlapping lapsed-Hold deletes can deadlock even when the two Bookings want different Seats.
			try {
				return insert(booking, now);
			} catch (PessimisticLockingFailureException again) {
				return new HoldOutcome.Busy();
			}
		}
	}

	private HoldOutcome insert(Booking booking, Instant now) {
		List<UUID> seatIds = booking.seats().stream().map(BookedSeat::seatId).toList();
		try {
			return transaction.execute(status -> {
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
	public PayOutcome confirm(Booking booking, Instant now) {
		if (jpa.confirm(booking.id(), now) == 1) {
			return new PayOutcome.Paid(booking.confirmed());
		}
		return jpa.findById(booking.id()).map(BookingEntity::toBooking).<PayOutcome>map(current -> {
			BookingStatus status = current.statusAt(now);
			return status == BookingStatus.CONFIRMED ? new PayOutcome.AlreadyPaid(current)
				: new PayOutcome.NotPayable(status);
		}).orElseGet(PayOutcome.Gone::new);
	}

	/** Like {@link #confirm}, the row is read again in the same transaction when nothing was deleted. */
	@Override
	@Transactional
	public AbandonOutcome deletePending(Booking booking) {
		if (jpa.deletePending(booking.id()) == 1) {
			return new AbandonOutcome.Abandoned();
		}
		return jpa.findById(booking.id()).<AbandonOutcome>map(current -> new AbandonOutcome.NotPending(
			current.toBooking().status())).orElseGet(AbandonOutcome.Gone::new);
	}
}
