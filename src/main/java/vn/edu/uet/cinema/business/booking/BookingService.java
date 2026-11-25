package vn.edu.uet.cinema.business.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.auditorium.Seat;
import vn.edu.uet.cinema.business.showtime.SeatPricing;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.business.showtime.inventory.AbandonOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.BookedSeat;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;
import vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.PayOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;

public class BookingService {

	private static final Duration HOLD = Duration.ofMinutes(10);
	private static final int MAX_SEATS = 8;
	public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

	private final ShowtimeService showtimes;
	private final BookingRepository bookings;
	private final SeatInventory inventory;
	private final AntiHoardingGuard antiHoarding;

	public BookingService(ShowtimeService showtimes, BookingRepository bookings, SeatInventory inventory) {
		this(showtimes, bookings, inventory, new AntiHoardingGuard());
	}

	public BookingService(ShowtimeService showtimes, BookingRepository bookings, SeatInventory inventory,
		AntiHoardingGuard antiHoarding) {
		this.showtimes = showtimes;
		this.bookings = bookings;
		this.inventory = inventory;
		this.antiHoarding = antiHoarding;
	}

	public Booking book(UUID customerId, UUID showtimeId, List<UUID> seatIds, Instant now) {
		return book(customerId, showtimeId, seatIds, now, null);
	}

	public Booking book(UUID customerId, UUID showtimeId, List<UUID> seatIds, Instant now, String clientKey) {
		antiHoarding.checkHoldAllowed(customerId, clientKey, now);
		if (seatIds.isEmpty() || seatIds.size() > MAX_SEATS) {
			throw new InvalidBookingException("A Booking has 1 to " + MAX_SEATS + " Seats");
		}
		if (Set.copyOf(seatIds).size() != seatIds.size()) {
			throw new InvalidBookingException("Duplicate seatIds");
		}
		Showtime showtime = showtimes.getScheduled(showtimeId);
		if (!now.isBefore(showtime.startsAt())) {
			throw new ConflictException("Showtime has already started");
		}
		Map<UUID, Seat> seats = showtimes.seatsById(showtime.auditoriumId());
		long basePrice = showtime.basePrice();
		List<BookedSeat> booked = seatIds.stream().map(id -> {
			Seat seat = seats.get(id);
			if (seat == null) {
				throw new InvalidBookingException("Seat " + id + " is not in the Showtime's Auditorium");
			}
			return new BookedSeat(id, SeatPricing.price(seat.type(), basePrice));
		}).toList();
		HoldOutcome outcome = inventory.hold(new Booking(UUID.randomUUID(), customerId, showtimeId,
			BookingStatus.PENDING, now, now.plus(HOLD), booked), now);
		return switch (outcome) {
			case HoldOutcome.Held held -> {
				antiHoarding.recordHoldCreated(customerId, clientKey, held.booking().id(),
					held.booking().expiresAt(), now);
				yield held.booking();
			}
			case HoldOutcome.SeatsTaken taken -> throw new SeatsTakenException(taken.seatIds());
			case HoldOutcome.ShowtimeCancelled cancelled -> throw new ConflictException("Showtime is Cancelled");
			case HoldOutcome.ShowtimeGone gone -> throw new ConflictException("Showtime no longer exists");
			case HoldOutcome.CustomerHasPending pending ->
				throw new ConflictException("Customer already has a Pending Booking"
					+ (pending.bookingId() != null ? " " + pending.bookingId() : ""));
			case HoldOutcome.Busy busy ->
				throw new ConflictException("Seats are being booked by others right now, try again");
		};
	}

	/** The Customer's own Bookings, newest first. */
	public List<Booking> list(UUID customerId) {
		return bookings.findByCustomer(customerId);
	}

	/** A page of the Customer's own Bookings, newest first. */
	public PageSlice<Booking> list(UUID customerId, PageQuery query) {
		return bookings.findByCustomer(customerId, query);
	}

	/** Someone else's Booking is reported as not found. */
	public Booking get(UUID customerId, UUID id) {
		return bookings.findById(id).filter(booking -> booking.customerId().equals(customerId))
			.orElseThrow(() -> new NotFoundException("Booking not found"));
	}

	/** Simulated payment: always succeeds, so only the Hold decides. Paying a Confirmed Booking returns it. */
	public Booking pay(UUID customerId, UUID id, Instant now) {
		return pay(customerId, id, now, null);
	}

	/**
	 * Idempotent payment with an optional {@code Idempotency-Key}. Retrying with the same key returns the same result
	 * without creating a duplicate transaction; reusing a key on a different Booking fails with 409.
	 */
	public Booking pay(UUID customerId, UUID id, Instant now, String idempotencyKey) {
		String validatedKey = validateIdempotencyKey(idempotencyKey);
		// Not confirmed by us: it was already, or a concurrent payment just did, or the Hold has lapsed or the
		// Booking was deleted meanwhile. The adapter tells which, in the transaction of its conditional update.
		PayOutcome outcome = validatedKey == null
			? inventory.pay(customerId, id, now)
			: inventory.pay(customerId, id, now, validatedKey);
		return switch (outcome) {
			case PayOutcome.Paid paid -> {
				antiHoarding.recordHoldReleased(paid.confirmed().id());
				yield paid.confirmed();
			}
			case PayOutcome.AlreadyPaid already -> {
				antiHoarding.recordHoldReleased(already.current().id());
				yield already.current();
			}
			case PayOutcome.NotPayable notPayable -> throw new ConflictException(
				"Booking is " + notPayable.status() + ", only a Pending Booking can be paid");
			case PayOutcome.IdempotencyKeyConflict conflict -> throw new ConflictException(
				"Idempotency-Key is already used for a different Booking");
			case PayOutcome.Gone gone -> throw new NotFoundException("Booking not found");
		};
	}

	private static String validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null) {
			return null;
		}
		if (idempotencyKey.indexOf('\0') >= 0) {
			throw new InvalidBookingException("Idempotency-Key must not contain a NUL character");
		}
		String trimmed = idempotencyKey.strip();
		if (trimmed.isEmpty()) {
			throw new InvalidBookingException("Idempotency-Key must not be blank");
		}
		if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new InvalidBookingException("Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
		}
		return trimmed;
	}

	/** An Expired Booking can be abandoned too: it is removed like any lapsed Hold. */
	public void abandon(UUID customerId, UUID id) {
		abandon(customerId, id, Instant.now());
	}

	public void abandon(UUID customerId, UUID id, Instant now) {
		// Nothing deleted: it is Confirmed or Refunded, was paid just now, or is already gone (then this is 404).
		// The adapter tells which, in the transaction of its conditional delete.
		switch (inventory.abandon(customerId, id)) {
			case AbandonOutcome.Abandoned abandoned -> {
				antiHoarding.recordHoldReleased(id);
				antiHoarding.recordAbandon(customerId, now);
			}
			case AbandonOutcome.NotPending notPending -> throw new ConflictException(
				"Booking is " + notPending.status() + ", only a Pending Booking can be abandoned");
			case AbandonOutcome.Gone gone -> throw new NotFoundException("Booking not found");
		}
	}
}
