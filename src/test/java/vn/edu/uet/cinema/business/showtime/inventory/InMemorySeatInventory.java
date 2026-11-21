package vn.edu.uet.cinema.business.showtime.inventory;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.booking.BookingRepository;
import vn.edu.uet.cinema.business.showtime.InMemoryShowtimes;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeStatus;

/**
 * In-memory {@link SeatInventory} and {@link BookingRepository} for business tests. It owns the only Booking rows.
 * Cancelling and deleting a Showtime change it through {@link InMemoryShowtimes}, as the adapter's one transaction
 * touches both tables. Every method is synchronized, so a test may call it from several threads; deadlocks, retries
 * and database locks stay covered against PostgreSQL.
 */
public class InMemorySeatInventory implements SeatInventory, BookingRepository {

	private final InMemoryShowtimes showtimes;
	private final Map<UUID, Booking> rows = new LinkedHashMap<>();

	public InMemorySeatInventory(InMemoryShowtimes showtimes) {
		this.showtimes = showtimes;
	}

	/** Test helper: stores a Booking as it is, as if written earlier. */
	public synchronized void store(Booking booking) {
		rows.put(booking.id(), booking);
	}

	/** Test helper: a copy of the Booking rows by id. */
	public synchronized Map<UUID, Booking> rows() {
		return Map.copyOf(rows);
	}

	@Override
	public synchronized Optional<Booking> findById(UUID id) {
		return Optional.ofNullable(rows.get(id));
	}

	@Override
	public synchronized List<Booking> findByCustomer(UUID customerId) {
		return rows.values().stream().filter(b -> b.customerId().equals(customerId))
			.sorted(Comparator.comparing(Booking::createdAt).reversed()).toList();
	}

	@Override
	public synchronized PageSlice<Booking> findByCustomer(UUID customerId, PageQuery query) {
		List<Booking> matching = rows.values().stream().filter(b -> b.customerId().equals(customerId))
			.sorted(Comparator.comparing(Booking::createdAt).reversed()).toList();
		int fromIndex = Math.min(query.offset(), matching.size());
		int toIndex = Math.min(fromIndex + query.size(), matching.size());
		return new PageSlice<>(matching.subList(fromIndex, toIndex), query.page(), query.size(), matching.size());
	}

	@Override
	public synchronized Optional<Booking> findLivePending(UUID customerId, Instant now) {
		return rows.values().stream()
			.filter(b -> b.customerId().equals(customerId) && b.statusAt(now) == BookingStatus.PENDING).findFirst();
	}

	@Override
	public synchronized HoldOutcome hold(Booking pending, Instant now) {
		if (showtimes.findById(pending.showtimeId()).filter(s -> s.status() == ShowtimeStatus.CANCELLED)
			.isPresent()) {
			return new HoldOutcome.ShowtimeCancelled();
		}
		List<UUID> seatIds = pending.seats().stream().map(BookedSeat::seatId).toList();
		rows.values().removeIf(b -> b.statusAt(now) == BookingStatus.EXPIRED && (b.customerId().equals(pending.customerId())
			|| b.showtimeId().equals(pending.showtimeId()) && b.seats().stream().anyMatch(s -> seatIds.contains(s.seatId()))));
		if (rows.values().stream().anyMatch(b -> b.customerId().equals(pending.customerId())
			&& b.status() == BookingStatus.PENDING)) {
			return new HoldOutcome.CustomerHasPending();
		}
		// Every remaining row of the Showtime counts, as for UNIQUE(showtime_id, seat_id).
		Set<UUID> taken = rows.values().stream().filter(b -> b.showtimeId().equals(pending.showtimeId()))
			.flatMap(b -> b.seats().stream()).map(BookedSeat::seatId).collect(Collectors.toSet());
		List<UUID> clash = seatIds.stream().filter(taken::contains).toList();
		if (!clash.isEmpty()) {
			return new HoldOutcome.SeatsTaken(clash);
		}
		rows.put(pending.id(), pending);
		return new HoldOutcome.Held(pending);
	}

	@Override
	public synchronized PayOutcome pay(Booking booking, Instant now) {
		Booking current = rows.get(booking.id());
		if (current == null) {
			return new PayOutcome.Gone();
		}
		BookingStatus status = current.statusAt(now);
		if (status == BookingStatus.PENDING) {
			rows.put(current.id(), current.confirmed());
			return new PayOutcome.Paid(booking.confirmed());
		}
		return status == BookingStatus.CONFIRMED ? new PayOutcome.AlreadyPaid(current)
			: new PayOutcome.NotPayable(status);
	}

	@Override
	public synchronized AbandonOutcome abandon(Booking booking) {
		Booking current = rows.get(booking.id());
		if (current == null) {
			return new AbandonOutcome.Gone();
		}
		if (current.status() != BookingStatus.PENDING) {
			return new AbandonOutcome.NotPending(current.status());
		}
		rows.remove(current.id());
		return new AbandonOutcome.Abandoned();
	}

	@Override
	public synchronized Map<UUID, SeatStatus> statuses(UUID showtimeId, Instant now) {
		Map<UUID, SeatStatus> statuses = new HashMap<>();
		for (Booking b : rows.values()) {
			if (b.showtimeId().equals(showtimeId) && b.occupiesSeatsAt(now)) {
				SeatStatus status = b.status() == BookingStatus.PENDING ? SeatStatus.HELD : SeatStatus.SOLD;
				b.seats().forEach(seat -> statuses.put(seat.seatId(), status));
			}
		}
		return statuses;
	}

	@Override
	public synchronized CancelOutcome cancel(UUID showtimeId, Cancellation cancellation, Instant now) {
		Showtime s = showtimes.findById(showtimeId).orElse(null);
		if (s == null || s.status() != ShowtimeStatus.SCHEDULED || !s.endsAt().isAfter(now)) {
			return new CancelOutcome.NotCancellable();
		}
		showtimes.replace(new Showtime(s.id(), s.movieId(), s.auditoriumId(), s.startsAt(), s.endsAt(), s.basePrice(),
			ShowtimeStatus.CANCELLED, cancellation));
		rows.values().removeIf(b -> b.showtimeId().equals(showtimeId) && b.status() == BookingStatus.PENDING);
		rows.replaceAll((bookingId, b) -> b.showtimeId().equals(showtimeId) && b.status() == BookingStatus.CONFIRMED
			? new Booking(b.id(), b.customerId(), b.showtimeId(), BookingStatus.REFUNDED, b.createdAt(), b.expiresAt(),
				b.seats())
			: b);
		return new CancelOutcome.Cancelled();
	}

	@Override
	public synchronized DeleteOutcome delete(UUID showtimeId, Instant now) {
		rows.values().removeIf(b -> b.showtimeId().equals(showtimeId) && b.statusAt(now) == BookingStatus.EXPIRED);
		// fk_booking_showtime is ON DELETE RESTRICT.
		if (rows.values().stream().anyMatch(b -> b.showtimeId().equals(showtimeId))) {
			return new DeleteOutcome.HasBookings();
		}
		showtimes.remove(showtimeId);
		return new DeleteOutcome.Deleted();
	}
}
