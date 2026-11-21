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

/**
 * Strategy 4 (single-writer): A single writer per Showtime coordinated via JVM {@link ReentrantLock}
 * coupled with an in-memory seat occupancy read model (ADR 0013).
 *
 * Fast path: lock-free check against in-memory model rejects taken seats with zero DB access.
 * Protected path: acquires lane lock (tryLock with timeout), re-checks in memory, delegates to
 * underlying SeatInventory, and updates the read model strictly post-commit.
 */
public class SingleWriterSeatInventory implements SeatInventory {

	private static final long DEFAULT_LOCK_TIMEOUT_SECONDS = 2;

	private final SeatInventory delegate;
	private final long lockTimeoutSeconds;

	private final ConcurrentHashMap<UUID, ReentrantLock> lanes = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, ShowtimeSeats> models = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, AtomicLong> versions = new ConcurrentHashMap<>();

	public SingleWriterSeatInventory(SeatInventory delegate) {
		this(delegate, DEFAULT_LOCK_TIMEOUT_SECONDS);
	}

	public SingleWriterSeatInventory(SeatInventory delegate, long lockTimeoutSeconds) {
		this.delegate = delegate;
		this.lockTimeoutSeconds = lockTimeoutSeconds;
	}

	@Override
	public HoldOutcome hold(Booking pending, Instant now) {
		UUID showtimeId = pending.showtimeId();
		List<UUID> requestedSeatIds = pending.seats().stream().map(BookedSeat::seatId).toList();

		// 1. Lock-free fast-path pre-check
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
		try {
			acquired = lane.tryLock(lockTimeoutSeconds, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new HoldOutcome.Busy();
		}

		if (!acquired) {
			return new HoldOutcome.Busy();
		}

		try {
			// 3. Re-check in-memory model inside the lane
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
				boolean sold = held.booking().status() == BookingStatus.CONFIRMED
					|| held.booking().status() == BookingStatus.REFUNDED;
				for (BookedSeat seat : held.booking().seats()) {
					currentModel.seats.put(seat.seatId(), new SeatEntry(
						held.booking().id(), held.booking().expiresAt(), sold
					));
				}
			} else if (outcome instanceof HoldOutcome.SeatsTaken takenOutcome) {
				// Drift fallback: update taken seats into cache
				for (UUID seatId : takenOutcome.seatIds()) {
					currentModel.seats.put(seatId, new SeatEntry(null, null, true));
				}
			} else if (outcome instanceof HoldOutcome.ShowtimeCancelled || outcome instanceof HoldOutcome.ShowtimeGone) {
				evictShowtime(showtimeId);
			}

			return outcome;
		} finally {
			lane.unlock();
		}
	}

	@Override
	public PayOutcome pay(Booking booking, Instant now) {
		PayOutcome outcome = delegate.pay(booking, now);
		if (outcome instanceof PayOutcome.Paid) {
			ShowtimeSeats model = models.get(booking.showtimeId());
			if (model != null) {
				for (BookedSeat seat : booking.seats()) {
					model.seats.computeIfPresent(seat.seatId(), (seatId, entry) ->
						new SeatEntry(booking.id(), null, true)
					);
				}
			}
		}
		return outcome;
	}

	@Override
	public AbandonOutcome abandon(Booking booking) {
		versionFor(booking.showtimeId()).incrementAndGet();
		AbandonOutcome outcome = delegate.abandon(booking);
		if (outcome instanceof AbandonOutcome.Abandoned) {
			ShowtimeSeats model = models.get(booking.showtimeId());
			if (model != null) {
				for (BookedSeat seat : booking.seats()) {
					// Conditional remove: remove if entry holds this bookingId or was cold-loaded without id
					model.seats.computeIfPresent(seat.seatId(), (seatId, entry) ->
						(entry.bookingId == null || booking.id().equals(entry.bookingId)) ? null : entry
					);
				}
			}
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
		versionFor(showtimeId).incrementAndGet();
		CancelOutcome outcome = delegate.cancel(showtimeId, cancellation, now);
		evictShowtime(showtimeId);
		return outcome;
	}

	@Override
	public DeleteOutcome delete(UUID showtimeId, Instant now) {
		versionFor(showtimeId).incrementAndGet();
		DeleteOutcome outcome = delegate.delete(showtimeId, now);
		evictShowtime(showtimeId);
		return outcome;
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
			if (v0 == version.get()) {
				ShowtimeSeats newModel = new ShowtimeSeats();
				for (Map.Entry<UUID, SeatStatus> entry : currentStatuses.entrySet()) {
					boolean sold = entry.getValue() == SeatStatus.SOLD;
					// A held status from cold read is treated as live until time lapse
					Instant expires = sold ? null : now.plusSeconds(600);
					newModel.seats.put(entry.getKey(), new SeatEntry(null, expires, sold));
				}
				models.put(showtimeId, newModel);
				return newModel;
			}
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
}
