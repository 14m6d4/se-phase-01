package vn.edu.uet.cinema.business.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.auditorium.Seat;
import vn.edu.uet.cinema.business.showtime.SeatPricing;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;

public class BookingService {

	private static final Duration HOLD = Duration.ofMinutes(10);
	private static final int MAX_SEATS = 8;

	private final ShowtimeService showtimes;
	private final AuditoriumService auditoriums;
	private final BookingRepository bookings;

	public BookingService(ShowtimeService showtimes, AuditoriumService auditoriums, BookingRepository bookings) {
		this.showtimes = showtimes;
		this.auditoriums = auditoriums;
		this.bookings = bookings;
	}

	public Booking book(UUID customerId, UUID showtimeId, List<UUID> seatIds, Instant now) {
		if (seatIds.isEmpty() || seatIds.size() > MAX_SEATS) {
			throw new InvalidBookingException("A Booking has 1 to " + MAX_SEATS + " Seats");
		}
		if (Set.copyOf(seatIds).size() != seatIds.size()) {
			throw new InvalidBookingException("Duplicate seatIds");
		}
		Showtime showtime = showtimes.get(showtimeId);
		if (!now.isBefore(showtime.startsAt())) {
			throw new ConflictException("Showtime has already started");
		}
		Map<UUID, Seat> seats = auditoriums.get(showtime.auditoriumId()).seats().stream()
			.collect(Collectors.toMap(Seat::id, Function.identity()));
		List<BookedSeat> booked = seatIds.stream().map(id -> {
			Seat seat = seats.get(id);
			if (seat == null) {
				throw new InvalidBookingException("Seat " + id + " is not in the Showtime's Auditorium");
			}
			return new BookedSeat(id, SeatPricing.price(seat.type(), showtime.basePrice()));
		}).toList();
		bookings.findLivePending(customerId, now).ifPresent(pending -> {
			throw new ConflictException("Customer already has a Pending Booking " + pending.id());
		});
		return bookings.saveNew(new Booking(UUID.randomUUID(), customerId, showtimeId, BookingStatus.PENDING, now,
			now.plus(HOLD), booked), now);
	}

	/** The Customer's own Bookings, newest first. */
	public List<Booking> list(UUID customerId) {
		return bookings.findByCustomer(customerId);
	}

	/** Someone else's Booking is reported as not found. */
	public Booking get(UUID customerId, UUID id) {
		return bookings.findById(id).filter(booking -> booking.customerId().equals(customerId))
			.orElseThrow(() -> new NotFoundException("Booking not found"));
	}

	/** Simulated payment: always succeeds, so only the Hold decides. */
	public Booking pay(UUID customerId, UUID id, Instant now) {
		Booking booking = get(customerId, id);
		BookingStatus status = booking.statusAt(now);
		if (status != BookingStatus.PENDING) {
			throw new ConflictException("Booking is " + status + ", only a Pending Booking can be paid");
		}
		if (!bookings.confirm(id, now)) {
			throw new ConflictException("Booking is no longer Pending");
		}
		return new Booking(booking.id(), booking.customerId(), booking.showtimeId(), BookingStatus.CONFIRMED,
			booking.createdAt(), booking.expiresAt(), booking.seats());
	}

	public void abandon(UUID customerId, UUID id) {
		// Checked again by the delete itself, in case it is being paid right now.
		if (get(customerId, id).status() == BookingStatus.CONFIRMED || !bookings.deletePending(id)) {
			throw new ConflictException("A Confirmed Booking cannot be abandoned");
		}
	}
}
