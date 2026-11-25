package vn.edu.uet.cinema.business.showtime.inventory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Every change to a Showtime's Seats, and every reason one becomes free, goes through here (ADR 0009). Each method is
 * one use case and one transaction in the adapter, and reports what happened as an outcome rather than a boolean
 * the caller has to explain by reading again.
 */
public interface SeatInventory {

	/**
	 * Takes a Hold for a new Pending Booking after deleting, in the same transaction, the lapsed Holds (as of
	 * {@code now}) on its Seats and the Customer's own lapsed Pending Booking. The transaction starts by share-locking
	 * the Showtime row (ADR 0005). A transaction that loses a deadlock is retried once.
	 */
	HoldOutcome hold(Booking pending, Instant now);

	/** Makes the Booking Confirmed if it is Pending with a Hold still running at {@code now}. */
	PayOutcome pay(UUID customerId, UUID bookingId, Instant now);

	/**
	 * Idempotent payment with an optional client-supplied {@code idempotencyKey}. Retrying with the same key for the
	 * same Booking returns the same outcome without a duplicate charge; reusing a key for a different Booking reports
	 * {@link PayOutcome.IdempotencyKeyConflict}.
	 */
	default PayOutcome pay(UUID customerId, UUID bookingId, Instant now, String idempotencyKey) {
		return pay(customerId, bookingId, now);
	}

	default PayOutcome pay(Booking booking, Instant now) {
		return pay(booking.customerId(), booking.id(), now);
	}

	/** Deletes the Booking if it is Pending, Expired ones included. */
	AbandonOutcome abandon(UUID customerId, UUID bookingId);

	default AbandonOutcome abandon(Booking booking) {
		return abandon(booking.customerId(), booking.id());
	}

	/** The Seats of the Showtime that are HELD or SOLD at {@code now}. Missing Seats are AVAILABLE. */
	Map<UUID, SeatStatus> statuses(UUID showtimeId, Instant now);

	/**
	 * Cancels the Showtime if it is Scheduled and has not ended at {@code now}. In the same transaction, and in this
	 * order, its Pending Bookings are deleted and its Confirmed ones become Refunded (ADR 0005).
	 */
	CancelOutcome cancel(UUID showtimeId, Cancellation cancellation, Instant now);

	/** Deletes the Showtime and, in the same transaction, its Holds that have lapsed at {@code now} (ADR 0004). */
	DeleteOutcome delete(UUID showtimeId, Instant now);
}
