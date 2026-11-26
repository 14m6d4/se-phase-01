package vn.edu.uet.cinema.business.showtime.inventory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

/**
 * Strategy 4 (single-writer): A single writer per Showtime coordinated via JVM {@link ReentrantLock}
 * coupled with an in-memory seat occupancy read model (ADR 0013).
 *
 * Fast path: lock-free check against in-memory state rejects active pending holds (B4 precedence) and
 * taken seats with zero DB access.
 * Protected path: acquires lane lock (tryLock with timeout), re-checks in memory, delegates to
 * underlying SeatInventory, and updates the read model strictly post-commit.
 */
public class SingleWriterSeatInventory implements SeatInventory {

	private static final long DEFAULT_LOCK_TIMEOUT_SECONDS = 2;
	private static final int MAX_INDEX_ENTRIES = 10_000;
	private static final LongConsumer NOOP_OBSERVER = nanos -> { };

	private final SeatInventory delegate;
	private final long lockTimeoutSeconds;
	private final LongConsumer lockWaitObserver;

	private final ConcurrentHashMap<UUID, ReentrantLock> lanes = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, ShowtimeSeats> models = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, AtomicLong> versions = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, HeldBookingRef> bookingIndex = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, PendingHoldRef> customerPending = new ConcurrentHashMap<>();

	public SingleWriterSeatInventory(SeatInventory delegate) {
		this(delegate, DEFAULT_LOCK_TIMEOUT_SECONDS, NOOP_OBSERVER);
	}

	public SingleWriterSeatInventory(SeatInventory delegate, long lockTimeoutSeconds) {
		this(delegate, lockTimeoutSeconds, NOOP_OBSERVER);
	}

	public SingleWriterSeatInventory(SeatInventory delegate, long lockTimeoutSeconds, LongConsumer lockWaitObserver) {
		this.delegate = delegate;
		this.lockTimeoutSeconds = lockTimeoutSeconds;
		this.lockWaitObserver = lockWaitObserver != null ? lockWaitObserver : NOOP_OBSERVER;
	}

	@Override
	public HoldOutcome hold(Booking pending, Instant now) {
		UUID showtimeId = pending.showtimeId();
		List<UUID> requestedSeatIds = pending.seats().stream().map(BookedSeat::seatId).toList();

		// 1. Lock-free fast-path pre-check: enforce CustomerHasPending before SeatsTaken (B4)
		HoldOutcome pendingConflict = findLiveCustomerPending(pending, now);
		if (pendingConflict != null) {
			return pendingConflict;
		}
		ShowtimeSeats model = models.get(showtimeId);
		if (model != null) {
			List<UUID> taken = findTakenInModel(model, requestedSeatIds, now);
			if (!taken.isEmpty()) {
				return new HoldOutcome.SeatsTaken(taken);
			}
		}

		// 2. Protected path: acquire per-showtime lane
		ReentrantLock lane = laneFor(showtimeId);
		boolean acquired;
		long lockStart = System.nanoTime();
		try {
			acquired = lane.tryLock(lockTimeoutSeconds, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new HoldOutcome.Busy();
		} finally {
			lockWaitObserver.accept(System.nanoTime() - lockStart);
		}

		if (!acquired) {
			return new HoldOutcome.Busy();
		}

		try {
			// 3. Re-check customer pending and in-memory model inside the lane
			HoldOutcome lanePendingConflict = findLiveCustomerPending(pending, now);
			if (lanePendingConflict != null) {
				return lanePendingConflict;
			}

			ShowtimeSeats currentModel = models.get(showtimeId);
			if (currentModel == null) {
				currentModel = loadModel(showtimeId, now);
			}

			List<UUID> taken = findTakenInModel(currentModel, requestedSeatIds, now);
			if (!taken.isEmpty()) {
				return new HoldOutcome.SeatsTaken(taken);
			}

			// 4. Delegate to underlying inventory
			HoldOutcome outcome = delegate.hold(pending, now);

			// 5. Update in-memory model strictly post-commit
			if (outcome instanceof HoldOutcome.Held held) {
				Booking saved = held.booking();
				boolean sold = saved.status() == BookingStatus.CONFIRMED
					|| saved.status() == BookingStatus.REFUNDED;
				for (BookedSeat seat : saved.seats()) {
					currentModel.seats.put(seat.seatId(), new SeatEntry(
						saved.id(), saved.expiresAt(), sold
					));
				}
				if (saved.status() == BookingStatus.PENDING) {
					pruneExpiredIndexEntries(now);
					List<UUID> heldSeats = saved.seats().stream().map(BookedSeat::seatId).toList();
					bookingIndex.put(saved.id(),
						new HeldBookingRef(showtimeId, saved.customerId(), heldSeats, saved.expiresAt()));
					customerPending.put(saved.customerId(),
						new PendingHoldRef(saved.id(), showtimeId, saved.expiresAt()));
				}
			} else if (outcome instanceof HoldOutcome.SeatsTaken) {
				// Drift fallback: evict stale showtime model so next caller reloads exact HELD/SOLD truth.
				models.remove(showtimeId);
			} else if (outcome instanceof HoldOutcome.ShowtimeCancelled || outcome instanceof HoldOutcome.ShowtimeGone) {
				evictShowtime(showtimeId);
			}

			return outcome;
		} finally {
			lane.unlock();
		}
	}

	@Override
	public PayOutcome pay(UUID customerId, UUID bookingId, Instant now) {
		PayOutcome outcome = delegate.pay(customerId, bookingId, now);
		handlePayOutcome(customerId, bookingId, outcome);
		return outcome;
	}

	@Override
	public PayOutcome pay(UUID customerId, UUID bookingId, Instant now, String idempotencyKey) {
		PayOutcome outcome = delegate.pay(customerId, bookingId, now, idempotencyKey);
		handlePayOutcome(customerId, bookingId, outcome);
		return outcome;
	}

	@Override
	public PayOutcome pay(Booking booking, Instant now) {
		PayOutcome outcome = delegate.pay(booking, now);
		handlePayOutcome(booking.customerId(), booking.id(), outcome);
		return outcome;
	}

	@Override
	public AbandonOutcome abandon(UUID customerId, UUID bookingId) {
		HeldBookingRef ref = bookingIndex.get(bookingId);
		AbandonOutcome outcome = delegate.abandon(customerId, bookingId);
		if (outcome instanceof AbandonOutcome.Abandoned) {
			bookingIndex.remove(bookingId);
			customerPending.computeIfPresent(customerId, (k, v) -> bookingId.equals(v.bookingId()) ? null : v);
			if (ref != null) {
				versionFor(ref.showtimeId()).incrementAndGet();
				removeBookingSeatsFromModel(ref.showtimeId(), ref.seatIds(), bookingId, true);
			} else {
				// Cold-loaded booking not in bookingIndex: bump versions and evict cold-loaded HELD models.
				versions.values().forEach(AtomicLong::incrementAndGet);
				models.entrySet().removeIf(entry -> entry.getValue().seats.values().stream()
					.anyMatch(se -> !se.sold && se.bookingId == null));
			}
		} else if (outcome instanceof AbandonOutcome.Gone) {
			bookingIndex.remove(bookingId);
			customerPending.computeIfPresent(customerId, (k, v) -> bookingId.equals(v.bookingId()) ? null : v);
			if (ref != null) {
				removeBookingSeatsFromModel(ref.showtimeId(), ref.seatIds(), bookingId, false);
			}
		} else if (outcome instanceof AbandonOutcome.NotPending) {
			bookingIndex.remove(bookingId);
			customerPending.computeIfPresent(customerId, (k, v) -> bookingId.equals(v.bookingId()) ? null : v);
		}
		return outcome;
	}

	@Override
	public AbandonOutcome abandon(Booking booking) {
		AbandonOutcome outcome = delegate.abandon(booking);
		UUID bookingId = booking.id();
		UUID showtimeId = booking.showtimeId();
		if (outcome instanceof AbandonOutcome.Abandoned) {
			versionFor(showtimeId).incrementAndGet();
			bookingIndex.remove(bookingId);
			customerPending.computeIfPresent(booking.customerId(),
				(k, v) -> bookingId.equals(v.bookingId()) ? null : v);
			List<UUID> seatIds = booking.seats().stream().map(BookedSeat::seatId).toList();
			removeBookingSeatsFromModel(showtimeId, seatIds, bookingId, true);
		} else if (outcome instanceof AbandonOutcome.Gone) {
			bookingIndex.remove(bookingId);
			customerPending.computeIfPresent(booking.customerId(),
				(k, v) -> bookingId.equals(v.bookingId()) ? null : v);
			List<UUID> seatIds = booking.seats().stream().map(BookedSeat::seatId).toList();
			removeBookingSeatsFromModel(showtimeId, seatIds, bookingId, false);
		} else if (outcome instanceof AbandonOutcome.NotPending) {
			bookingIndex.remove(bookingId);
			customerPending.computeIfPresent(booking.customerId(),
				(k, v) -> bookingId.equals(v.bookingId()) ? null : v);
		}
		return outcome;
	}

	@Override
	public Map<UUID, SeatStatus> statuses(UUID showtimeId, Instant now) {
		ShowtimeSeats model = models.get(showtimeId);
		if (model != null) {
			Map<UUID, SeatStatus> result = new HashMap<>();
			for (Map.Entry<UUID, SeatEntry> entry : model.seats.entrySet()) {
				SeatEntry se = entry.getValue();
				if (se.sold) {
					result.put(entry.getKey(), SeatStatus.SOLD);
				} else if (se.expiresAt != null && se.expiresAt.isAfter(now)) {
					result.put(entry.getKey(), SeatStatus.HELD);
				}
			}
			return result;
		}
		return delegate.statuses(showtimeId, now);
	}

	@Override
	public CancelOutcome cancel(UUID showtimeId, Cancellation cancellation, Instant now) {
		CancelOutcome outcome = delegate.cancel(showtimeId, cancellation, now);
		AtomicLong version = versions.get(showtimeId);
		if (version != null) {
			version.incrementAndGet();
		}
		evictShowtime(showtimeId);
		return outcome;
	}

	@Override
	public DeleteOutcome delete(UUID showtimeId, Instant now) {
		DeleteOutcome outcome = delegate.delete(showtimeId, now);
		AtomicLong version = versions.get(showtimeId);
		if (version != null) {
			version.incrementAndGet();
		}
		evictShowtime(showtimeId);
		return outcome;
	}

	private HoldOutcome findLiveCustomerPending(Booking pending, Instant now) {
		if (pending.status() != BookingStatus.PENDING) {
			return null;
		}
		PendingHoldRef existing = customerPending.get(pending.customerId());
		if (existing == null) {
			return null;
		}
		if (existing.expiresAt() != null && existing.expiresAt().isAfter(now)) {
			return new HoldOutcome.CustomerHasPending(existing.bookingId());
		}
		customerPending.remove(pending.customerId(), existing);
		return null;
	}

	private void handlePayOutcome(UUID customerId, UUID bookingId, PayOutcome outcome) {
		if (outcome instanceof PayOutcome.Paid paid) {
			markBookingSoldInModel(paid.confirmed());
			bookingIndex.remove(bookingId);
			customerPending.computeIfPresent(customerId, (k, v) -> bookingId.equals(v.bookingId()) ? null : v);
		} else if (outcome instanceof PayOutcome.AlreadyPaid already) {
			markBookingSoldInModel(already.current());
			bookingIndex.remove(bookingId);
			customerPending.computeIfPresent(customerId, (k, v) -> bookingId.equals(v.bookingId()) ? null : v);
		} else if (outcome instanceof PayOutcome.Gone || outcome instanceof PayOutcome.NotPayable) {
			bookingIndex.remove(bookingId);
			customerPending.computeIfPresent(customerId, (k, v) -> bookingId.equals(v.bookingId()) ? null : v);
		}
	}

	private void markBookingSoldInModel(Booking booking) {
		ShowtimeSeats model = models.get(booking.showtimeId());
		if (model != null) {
			for (BookedSeat seat : booking.seats()) {
				model.seats.put(seat.seatId(), new SeatEntry(booking.id(), null, true));
			}
		}
	}

	private void removeBookingSeatsFromModel(UUID showtimeId, List<UUID> seatIds, UUID bookingId,
		boolean removeColdLoaded) {
		ShowtimeSeats model = models.get(showtimeId);
		if (model == null) {
			return;
		}
		for (UUID seatId : seatIds) {
			model.seats.computeIfPresent(seatId, (id, entry) -> {
				if (bookingId.equals(entry.bookingId) || (removeColdLoaded && entry.bookingId == null && !entry.sold)) {
					return null;
				}
				return entry;
			});
		}
	}

	private void pruneExpiredIndexEntries(Instant now) {
		if (bookingIndex.size() >= MAX_INDEX_ENTRIES) {
			bookingIndex.entrySet().removeIf(
				e -> e.getValue().expiresAt() != null && !e.getValue().expiresAt().isAfter(now));
		}
		if (customerPending.size() >= MAX_INDEX_ENTRIES) {
			customerPending.entrySet().removeIf(
				e -> e.getValue().expiresAt() != null && !e.getValue().expiresAt().isAfter(now));
		}
	}

	private ReentrantLock laneFor(UUID showtimeId) {
		return lanes.computeIfAbsent(showtimeId, id -> new ReentrantLock());
	}

	private AtomicLong versionFor(UUID showtimeId) {
		return versions.computeIfAbsent(showtimeId, id -> new AtomicLong());
	}

	private void evictShowtime(UUID showtimeId) {
		models.remove(showtimeId);
		lanes.remove(showtimeId);
		versions.remove(showtimeId);
		bookingIndex.entrySet().removeIf(e -> showtimeId.equals(e.getValue().showtimeId()));
		customerPending.entrySet().removeIf(e -> showtimeId.equals(e.getValue().showtimeId()));
	}

	/**
	 * Loads seat statuses from the delegate under the lane lock.
	 * Solves the load-versus-release race condition via the per-showtime version counter.
	 */
	private ShowtimeSeats loadModel(UUID showtimeId, Instant now) {
		AtomicLong version = versionFor(showtimeId);
		while (true) {
			long v0 = version.get();
			Map<UUID, SeatStatus> currentStatuses = delegate.statuses(showtimeId, now);
			ShowtimeSeats newModel = new ShowtimeSeats();
			for (Map.Entry<UUID, SeatStatus> entry : currentStatuses.entrySet()) {
				boolean sold = entry.getValue() == SeatStatus.SOLD;
				// A held status from cold read is treated as live until time lapse
				Instant expires = sold ? null : now.plusSeconds(600);
				newModel.seats.put(entry.getKey(), new SeatEntry(null, expires, sold));
			}
			models.put(showtimeId, newModel);
			if (v0 == version.get()) {
				return newModel;
			}
			models.remove(showtimeId, newModel);
		}
	}

	private List<UUID> findTakenInModel(ShowtimeSeats model, List<UUID> requestedSeatIds, Instant now) {
		return requestedSeatIds.stream().filter(seatId -> {
			SeatEntry entry = model.seats.get(seatId);
			return entry != null && entry.isOccupied(now);
		}).toList();
	}

	private static final class ShowtimeSeats {
		final ConcurrentHashMap<UUID, SeatEntry> seats = new ConcurrentHashMap<>();
	}

	private record SeatEntry(UUID bookingId, Instant expiresAt, boolean sold) {
		boolean isOccupied(Instant now) {
			return sold || (expiresAt != null && expiresAt.isAfter(now));
		}
	}

	private record HeldBookingRef(UUID showtimeId, UUID customerId, List<UUID> seatIds, Instant expiresAt) {
	}

	private record PendingHoldRef(UUID bookingId, UUID showtimeId, Instant expiresAt) {
	}
}
