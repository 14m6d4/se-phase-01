package vn.edu.uet.cinema.persistence.booking;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.booking.BookedSeat;
import vn.edu.uet.cinema.business.booking.Booking;
import vn.edu.uet.cinema.business.booking.BookingRepository;

@Repository
class JpaBookingRepository implements BookingRepository {

	private final BookingEntityRepository jpa;

	JpaBookingRepository(BookingEntityRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Booking> findLivePending(UUID customerId, Instant now) {
		return jpa.findLivePending(customerId, now).map(BookingEntity::toBooking);
	}

	@Override
	@Transactional
	public Booking saveNew(Booking booking, Instant now) {
		try {
			jpa.deleteLapsedHolds(booking.customerId(), booking.showtimeId(),
				booking.seats().stream().map(BookedSeat::seatId).toList(), now);
			return jpa.saveAndFlush(BookingEntity.from(booking)).toBooking();
		} catch (DataIntegrityViolationException e) {
			// The insert that loses the race fails here (ADR 0004).
			if (hasConstraint(e, "uq_booked_seat_showtime_seat")) {
				throw new ConflictException("Seat already taken");
			}
			if (hasConstraint(e, "uq_booking_customer_pending")) {
				throw new ConflictException("Customer already has a Pending Booking");
			}
			if (hasConstraint(e, "fk_booking_showtime")) {
				throw new ConflictException("Showtime no longer exists");
			}
			throw e;
		} catch (PessimisticLockingFailureException e) {
			// A deadlock between overlapping lapsed-Hold deletes: someone else is taking these Seats right now.
			throw new ConflictException("Seat already taken");
		}
	}

	private static boolean hasConstraint(Throwable error, String expected) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation && expected.equals(violation.getConstraintName())) {
				return true;
			}
		}
		return false;
	}
}
