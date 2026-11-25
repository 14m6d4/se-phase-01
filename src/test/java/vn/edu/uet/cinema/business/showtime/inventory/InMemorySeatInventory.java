package vn.edu.uet.cinema.business.showtime.inventory;

import java.time.Instant;
import java.util.ArrayList;
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
import vn.edu.uet.cinema.business.booking.PaymentTransaction;
import vn.edu.uet.cinema.business.booking.PaymentTransactionRepository;
import vn.edu.uet.cinema.business.booking.PaymentTransactionStatus;
import vn.edu.uet.cinema.business.booking.PaymentTransactionType;
import vn.edu.uet.cinema.business.showtime.InMemoryShowtimes;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeStatus;

/**
 * In-memory {@link SeatInventory} and {@link BookingRepository} for business tests. It owns the only Booking rows.
 * Cancelling and deleting a Showtime change it through {@link InMemoryShowtimes}, as the adapter's one transaction
 * touches both tables. Every method is synchronized, so a test may call it from several threads; deadlocks, retries
 * and database locks stay covered against PostgreSQL.
 */
public class InMemorySeatInventory implements SeatInventory, BookingRepository, PaymentTransactionRepository {

	private final InMemoryShowtimes showtimes;
	private final Map<UUID, Booking> rows = new LinkedHashMap<>();
	private final List<PaymentTransaction> transactions = new ArrayList<>();

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
		Optional<Booking> livePending = rows.values().stream().filter(b -> b.customerId().equals(pending.customerId())
			&& b.status() == BookingStatus.PENDING).findFirst();
		if (livePending.isPresent()) {
			return new HoldOutcome.CustomerHasPending(livePending.get().id());
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
	public synchronized List<PaymentTransaction> findByBookingId(UUID bookingId) {
		return transactions.stream().filter(tx -> tx.bookingId().equals(bookingId)).toList();
	}

	@Override
	public synchronized Optional<PaymentTransaction> findByCustomerAndKey(UUID customerId, PaymentTransactionType type,
		String idempotencyKey) {
		if (idempotencyKey == null) {
			return Optional.empty();
		}
		return transactions.stream()
			.filter(tx -> tx.customerId().equals(customerId) && tx.type() == type
				&& idempotencyKey.equals(tx.idempotencyKey()))
			.findFirst();
	}

	@Override
	public synchronized PayOutcome pay(UUID customerId, UUID bookingId, Instant now) {
		return payInternal(customerId, bookingId, now, null);
	}

	@Override
	public synchronized PayOutcome pay(UUID customerId, UUID bookingId, Instant now, String idempotencyKey) {
		return payInternal(customerId, bookingId, now, idempotencyKey);
	}

	private PayOutcome payInternal(UUID customerId, UUID bookingId, Instant now, String idempotencyKey) {
		if (idempotencyKey != null) {
			Optional<PaymentTransaction> existing = findByCustomerAndKey(customerId, PaymentTransactionType.PAYMENT,
				idempotencyKey);
			if (existing.isPresent()) {
				PaymentTransaction tx = existing.get();
				if (!tx.bookingId().equals(bookingId)) {
					return new PayOutcome.IdempotencyKeyConflict();
				}
				if (tx.status() == PaymentTransactionStatus.REFUNDED) {
					return new PayOutcome.NotPayable(BookingStatus.EXPIRED);
				}
				Booking replay = rows.get(bookingId);
				if (replay == null || !replay.customerId().equals(customerId)) {
					return new PayOutcome.Gone();
				}
				BookingStatus status = replay.statusAt(now);
				return status == BookingStatus.CONFIRMED ? new PayOutcome.AlreadyPaid(replay)
					: new PayOutcome.NotPayable(status);
			}
		}
		Booking current = rows.get(bookingId);
		if (current == null || !current.customerId().equals(customerId)) {
			return new PayOutcome.Gone();
		}
		BookingStatus status = current.statusAt(now);
		if (status == BookingStatus.PENDING) {
			Booking confirmed = current.confirmed();
			rows.put(current.id(), confirmed);
			transactions.add(new PaymentTransaction(UUID.randomUUID(), confirmed.id(), customerId,
				PaymentTransactionType.PAYMENT, PaymentTransactionStatus.COMPLETED, confirmed.totalPrice(),
				idempotencyKey, now, now));
			return new PayOutcome.Paid(confirmed);
		}
		if (status == BookingStatus.CONFIRMED) {
			return new PayOutcome.AlreadyPaid(current);
		}
		if (status == BookingStatus.EXPIRED) {
			String lateKey = idempotencyKey != null ? idempotencyKey : "late-refund:" + current.id();
			if (findByCustomerAndKey(customerId, PaymentTransactionType.PAYMENT, lateKey).isEmpty()) {
				transactions.add(new PaymentTransaction(UUID.randomUUID(), current.id(), customerId,
					PaymentTransactionType.PAYMENT, PaymentTransactionStatus.REFUNDED, current.totalPrice(),
					lateKey, now, now));
			}
		}
		return new PayOutcome.NotPayable(status);
	}

	@Override
	public synchronized PayOutcome pay(Booking booking, Instant now) {
		return pay(booking.customerId(), booking.id(), now);
	}

	@Override
	public synchronized AbandonOutcome abandon(UUID customerId, UUID bookingId) {
		Booking current = rows.get(bookingId);
		if (current == null || !current.customerId().equals(customerId)) {
			return new AbandonOutcome.Gone();
		}
		if (current.status() != BookingStatus.PENDING) {
			return new AbandonOutcome.NotPending(current.status());
		}
		rows.remove(current.id());
		return new AbandonOutcome.Abandoned();
	}

	@Override
	public synchronized AbandonOutcome abandon(Booking booking) {
		return abandon(booking.customerId(), booking.id());
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
		if (s == null) {
			return new CancelOutcome.NotFound();
		}
		if (s.status() != ShowtimeStatus.SCHEDULED || !s.endsAt().isAfter(now)) {
			return new CancelOutcome.NotCancellable();
		}
		showtimes.replace(new Showtime(s.id(), s.movieId(), s.auditoriumId(), s.startsAt(), s.endsAt(), s.basePrice(),
			ShowtimeStatus.CANCELLED, cancellation));
		rows.values().removeIf(b -> b.showtimeId().equals(showtimeId) && b.status() == BookingStatus.PENDING);
		rows.replaceAll((bookingId, b) -> {
			if (b.showtimeId().equals(showtimeId) && b.status() == BookingStatus.CONFIRMED) {
				boolean alreadyRefunded = transactions.stream()
					.anyMatch(tx -> tx.bookingId().equals(b.id()) && tx.type() == PaymentTransactionType.REFUND);
				if (!alreadyRefunded) {
					transactions.add(new PaymentTransaction(UUID.randomUUID(), b.id(), b.customerId(),
						PaymentTransactionType.REFUND, PaymentTransactionStatus.REFUNDED, b.totalPrice(),
						"refund:" + b.id(), now, now));
				}
				return new Booking(b.id(), b.customerId(), b.showtimeId(), BookingStatus.REFUNDED, b.createdAt(),
					b.expiresAt(), b.seats());
			}
			return b;
		});
		return new CancelOutcome.Cancelled();
	}

	@Override
	public synchronized DeleteOutcome delete(UUID showtimeId, Instant now) {
		if (showtimes.findById(showtimeId).isEmpty()) {
			return new DeleteOutcome.NotFound();
		}
		rows.values().removeIf(b -> b.showtimeId().equals(showtimeId) && b.statusAt(now) == BookingStatus.EXPIRED);
		// fk_booking_showtime is ON DELETE RESTRICT.
		if (rows.values().stream().anyMatch(b -> b.showtimeId().equals(showtimeId))) {
			return new DeleteOutcome.HasBookings();
		}
		showtimes.remove(showtimeId);
		return new DeleteOutcome.Deleted();
	}
}
