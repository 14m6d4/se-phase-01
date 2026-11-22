package vn.edu.uet.cinema.business.showtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.auditorium.Seat;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.inventory.CancelOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.Cancellation;
import vn.edu.uet.cinema.business.showtime.inventory.CancellationReason;
import vn.edu.uet.cinema.business.showtime.inventory.DeleteOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SeatStatus;

public class ShowtimeService {

	private static final int MAX_NOTE_LENGTH = 200;

	private final MovieService movies;
	private final AuditoriumService auditoriums;
	private final ShowtimeRepository showtimes;
	private final SeatInventory inventory;
	private final Map<UUID, List<Seat>> layouts = new ConcurrentHashMap<>();

	public ShowtimeService(MovieService movies, AuditoriumService auditoriums, ShowtimeRepository showtimes,
		SeatInventory inventory) {
		this.movies = movies;
		this.auditoriums = auditoriums;
		this.showtimes = showtimes;
		this.inventory = inventory;
	}

	public Showtime create(UUID movieId, UUID auditoriumId, Instant startsAt, long basePrice) {
		if (basePrice <= 0) {
			throw new InvalidShowtimeException("Base price must be positive");
		}
		if (basePrice > SeatPricing.MAX_BASE_PRICE) {
			throw new InvalidShowtimeException("Base price must be at most " + SeatPricing.MAX_BASE_PRICE);
		}
		int durationMinutes = movies.get(movieId).durationMinutes();
		auditoriums.get(auditoriumId);
		Instant endsAt = startsAt.plusSeconds(((long) durationMinutes + 15) * 60);
		if (showtimes.existsOverlapping(auditoriumId, startsAt, endsAt)) {
			throw new ConflictException("Auditorium has an overlapping Showtime");
		}
		return showtimes.save(new Showtime(UUID.randomUUID(), movieId, auditoriumId, startsAt, endsAt, basePrice));
	}

	public Showtime get(UUID id) {
		return showtimes.findById(id).orElseThrow(() -> new NotFoundException("Showtime not found"));
	}

	/** A Showtime whose Seats can still be chosen: a Cancelled one is a conflict. */
	public Showtime getScheduled(UUID id) {
		Showtime showtime = get(id);
		if (showtime.status() == ShowtimeStatus.CANCELLED) {
			throw new ConflictException("Showtime is Cancelled");
		}
		return showtime;
	}

	/** The Seats of the Showtime's Auditorium in layout order, each priced from its Seat Type, keyed by Seat id. */
	public Map<UUID, PricedSeat> pricedSeats(Showtime showtime) {
		Map<UUID, PricedSeat> priced = new LinkedHashMap<>();
		layout(showtime.auditoriumId()).forEach(seat -> priced.put(seat.id(),
			new PricedSeat(seat, SeatPricing.price(seat.type(), showtime.basePrice()))));
		return priced;
	}

	/**
	 * An Auditorium's Seats, read once (ADR 0008). Auditoriums are not edited, and one with a Showtime cannot be
	 * deleted (ADR 0006), so the layout behind a Showtime is fixed on every instance. A change that lets an
	 * Auditorium's Seats change must evict it here.
	 */
	private List<Seat> layout(UUID auditoriumId) {
		// get() never locks; computeIfAbsent() can lock its bin even for a key that is already there.
		List<Seat> seats = layouts.get(auditoriumId);
		return seats != null ? seats : layouts.computeIfAbsent(auditoriumId, id -> auditoriums.get(id).seats());
	}

	/** Every Seat of a Scheduled Showtime with its price and status at {@code now}. */
	public List<SeatMapEntry> seatMap(UUID id, Instant now) {
		Showtime showtime = getScheduled(id);
		Map<UUID, SeatStatus> statuses = inventory.statuses(id, now);
		return pricedSeats(showtime).values().stream().map(p -> new SeatMapEntry(p,
			statuses.getOrDefault(p.seat().id(), SeatStatus.AVAILABLE))).toList();
	}

	public List<Showtime> list(Instant fromInclusive, Instant toExclusive, UUID cinemaId, UUID movieId) {
		return showtimes.findByStartsAtBetween(fromInclusive, toExclusive, cinemaId, movieId);
	}

	/** Its Pending Bookings are deleted and its Confirmed ones become Refunded. A blank note counts as no note. */
	public Showtime cancel(UUID id, CancellationReason reason, String note, Instant now) {
		String given = note == null || note.isBlank() ? null : note;
		if (reason == CancellationReason.OTHER && given == null) {
			throw new InvalidShowtimeException("A Cancellation Reason of Other needs a note");
		}
		if (given != null && given.length() > MAX_NOTE_LENGTH) {
			throw new InvalidShowtimeException("A cancellation note has at most " + MAX_NOTE_LENGTH + " characters");
		}
		Cancellation cancellation = new Cancellation(reason, given);
		return switch (inventory.cancel(id, cancellation, now)) {
			case CancelOutcome.Cancelled cancelled -> {
				Showtime showtime = get(id);
				yield new Showtime(showtime.id(), showtime.movieId(),
					showtime.auditoriumId(), showtime.startsAt(), showtime.endsAt(), showtime.basePrice(),
					ShowtimeStatus.CANCELLED, cancellation);
			}
			case CancelOutcome.NotCancellable notCancellable ->
				throw new ConflictException("Showtime is already Cancelled or has ended");
			case CancelOutcome.NotFound notFound ->
				throw new NotFoundException("Showtime not found");
		};
	}

	public void delete(UUID id, Instant now) {
		switch (inventory.delete(id, now)) {
			case DeleteOutcome.Deleted deleted -> {
			}
			case DeleteOutcome.HasBookings hasBookings -> throw new ConflictException("Showtime has Bookings");
			case DeleteOutcome.NotFound notFound -> throw new NotFoundException("Showtime not found");
		}
	}
}
