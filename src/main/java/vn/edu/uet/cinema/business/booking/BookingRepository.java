package vn.edu.uet.cinema.business.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository {

	Optional<Booking> findById(UUID id);

	/** Newest first. */
	List<Booking> findByCustomer(UUID customerId);

	/** The Customer's Pending Booking whose Hold is still running at {@code now}. */
	Optional<Booking> findLivePending(UUID customerId, Instant now);

	/**
	 * Inserts a new Booking after deleting, in the same transaction, the lapsed Holds (as of {@code now}) on its
	 * Seats and the Customer's own lapsed Pending Booking.
	 *
	 * @throws vn.edu.uet.cinema.business.ConflictException if a Seat is taken or the Customer already has a
	 * Pending Booking
	 */
	Booking saveNew(Booking booking, Instant now);

	/** Makes the Booking Confirmed if it is Pending with a Hold still running at {@code now}; false otherwise. */
	boolean confirm(UUID id, Instant now);

	/** Deletes the Booking if it is Pending; false otherwise. */
	boolean deletePending(UUID id);
}
