package vn.edu.uet.cinema.business.showtime.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import vn.edu.uet.cinema.business.auditorium.InMemoryAuditoriums;
import vn.edu.uet.cinema.business.showtime.InMemoryShowtimes;

class SingleWriterSeatInventoryTest {

	private final Instant now = Instant.now();

	@Test
	void fastPathRejectsTakenSeatsLockFree() {
		InMemorySeatInventory fake = new InMemorySeatInventory(new InMemoryShowtimes(new vn.edu.uet.cinema.business.auditorium.InMemoryAuditoriums()));
		SingleWriterSeatInventory singleWriter = new SingleWriterSeatInventory(fake);

		UUID showtimeId = UUID.randomUUID();
		UUID seatId = UUID.randomUUID();
		UUID customer1 = UUID.randomUUID();
		UUID customer2 = UUID.randomUUID();

		Booking b1 = new Booking(UUID.randomUUID(), customer1, showtimeId, BookingStatus.PENDING, now,
			now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));

		HoldOutcome outcome1 = singleWriter.hold(b1, now);
		assertThat(outcome1).isInstanceOf(HoldOutcome.Held.class);

		Booking b2 = new Booking(UUID.randomUUID(), customer2, showtimeId, BookingStatus.PENDING, now,
			now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));

		HoldOutcome outcome2 = singleWriter.hold(b2, now);
		assertThat(outcome2).isInstanceOf(HoldOutcome.SeatsTaken.class);
		assertThat(((HoldOutcome.SeatsTaken) outcome2).seatIds()).containsExactly(seatId);
	}

	@Test
	void timeoutOnContendedLaneReturnsBusyOutcome() throws Exception {
		CountDownLatch delegateEntered = new CountDownLatch(1);
		CountDownLatch releaseDelegate = new CountDownLatch(1);

		UUID showtimeId = UUID.randomUUID();
		UUID seatA = UUID.randomUUID();
		UUID seatB = UUID.randomUUID();

		SeatInventory blockingDelegate = new SeatInventory() {
			@Override
			public HoldOutcome hold(Booking pending, Instant now) {
				delegateEntered.countDown();
				try {
					releaseDelegate.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return new HoldOutcome.Held(pending);
			}

			@Override
			public PayOutcome pay(UUID customerId, UUID bookingId, Instant now) {
				return new PayOutcome.Paid(null);
			}

			@Override
			public PayOutcome pay(Booking booking, Instant now) {
				return new PayOutcome.Paid(booking.confirmed());
			}

			@Override
			public AbandonOutcome abandon(UUID customerId, UUID bookingId) {
				return new AbandonOutcome.Abandoned();
			}

			@Override
			public AbandonOutcome abandon(Booking booking) {
				return new AbandonOutcome.Abandoned();
			}

			@Override
			public Map<UUID, SeatStatus> statuses(UUID showtimeId, Instant now) {
				return Map.of();
			}

			@Override
			public CancelOutcome cancel(UUID showtimeId, Cancellation cancellation, Instant now) {
				return new CancelOutcome.Cancelled();
			}

			@Override
			public DeleteOutcome delete(UUID showtimeId, Instant now) {
				return new DeleteOutcome.Deleted();
			}
		};

		// 1 second lock timeout
		SingleWriterSeatInventory singleWriter = new SingleWriterSeatInventory(blockingDelegate, 1);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<HoldOutcome> firstHold = pool.submit(() -> {
				Booking b1 = new Booking(UUID.randomUUID(), UUID.randomUUID(), showtimeId, BookingStatus.PENDING, now,
					now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatA, 100_000)));
				return singleWriter.hold(b1, now);
			});

			assertThat(delegateEntered.await(5, TimeUnit.SECONDS)).isTrue();

			Future<HoldOutcome> secondHold = pool.submit(() -> {
				Booking b2 = new Booking(UUID.randomUUID(), UUID.randomUUID(), showtimeId, BookingStatus.PENDING, now,
					now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatB, 100_000)));
				return singleWriter.hold(b2, now);
			});

			HoldOutcome busyOutcome = secondHold.get(3, TimeUnit.SECONDS);
			assertThat(busyOutcome).isInstanceOf(HoldOutcome.Busy.class);

			releaseDelegate.countDown();
			HoldOutcome firstOutcome = firstHold.get(3, TimeUnit.SECONDS);
			assertThat(firstOutcome).isInstanceOf(HoldOutcome.Held.class);
		} finally {
			releaseDelegate.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void postAbandonImmediateHoldByAnotherCustomerSucceedsWithoutFalse409() {
		InMemorySeatInventory fake = new InMemorySeatInventory(new InMemoryShowtimes(new vn.edu.uet.cinema.business.auditorium.InMemoryAuditoriums()));
		SingleWriterSeatInventory singleWriter = new SingleWriterSeatInventory(fake);

		UUID showtimeId = UUID.randomUUID();
		UUID seatId = UUID.randomUUID();
		UUID customer1 = UUID.randomUUID();
		UUID customer2 = UUID.randomUUID();

		Booking b1 = new Booking(UUID.randomUUID(), customer1, showtimeId, BookingStatus.PENDING, now,
			now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));

		assertThat(singleWriter.hold(b1, now)).isInstanceOf(HoldOutcome.Held.class);
		assertThat(singleWriter.abandon(b1)).isEqualTo(new AbandonOutcome.Abandoned());

		Booking b2 = new Booking(UUID.randomUUID(), customer2, showtimeId, BookingStatus.PENDING, now,
			now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));

		// Must succeed immediately with no false 409
		assertThat(singleWriter.hold(b2, now)).isInstanceOf(HoldOutcome.Held.class);
	}

	@Test
	void payMarksSeatSoldInStatuses() {
		InMemorySeatInventory fake = new InMemorySeatInventory(new InMemoryShowtimes(new vn.edu.uet.cinema.business.auditorium.InMemoryAuditoriums()));
		SingleWriterSeatInventory singleWriter = new SingleWriterSeatInventory(fake);

		UUID showtimeId = UUID.randomUUID();
		UUID seatId = UUID.randomUUID();
		UUID customer = UUID.randomUUID();

		Booking b = new Booking(UUID.randomUUID(), customer, showtimeId, BookingStatus.PENDING, now,
			now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));

		singleWriter.hold(b, now);
		assertThat(singleWriter.statuses(showtimeId, now)).containsEntry(seatId, SeatStatus.HELD);

		singleWriter.pay(b, now);
		assertThat(singleWriter.statuses(showtimeId, now)).containsEntry(seatId, SeatStatus.SOLD);
	}

	@Test
	void cancellationEvictsShowtimeModel() {
		InMemoryAuditoriums auditoriums = new InMemoryAuditoriums();
		InMemoryShowtimes showtimes = new InMemoryShowtimes(auditoriums);
		InMemorySeatInventory fake = new InMemorySeatInventory(showtimes);
		SingleWriterSeatInventory singleWriter = new SingleWriterSeatInventory(fake);

		UUID showtimeId = UUID.randomUUID();
		UUID seatId = UUID.randomUUID();
		showtimes.save(new vn.edu.uet.cinema.business.showtime.Showtime(showtimeId, UUID.randomUUID(), UUID.randomUUID(),
			now, now.plus(2, ChronoUnit.HOURS), 100_000, vn.edu.uet.cinema.business.showtime.ShowtimeStatus.SCHEDULED, null));

		Booking b1 = new Booking(UUID.randomUUID(), UUID.randomUUID(), showtimeId, BookingStatus.PENDING, now,
			now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));
		singleWriter.hold(b1, now);

		CancelOutcome outcome = singleWriter.cancel(showtimeId, new Cancellation(CancellationReason.POWER_OUTAGE, "storm"), now);
		assertThat(outcome).isInstanceOf(CancelOutcome.Cancelled.class);

		// Model is evicted and bookings purged
		assertThat(singleWriter.statuses(showtimeId, now)).isEmpty();
	}

	@Test
	void deleteEvictsShowtimeModel() {
		InMemoryAuditoriums auditoriums = new InMemoryAuditoriums();
		InMemoryShowtimes showtimes = new InMemoryShowtimes(auditoriums);
		InMemorySeatInventory fake = new InMemorySeatInventory(showtimes);
		SingleWriterSeatInventory singleWriter = new SingleWriterSeatInventory(fake);

		UUID showtimeId = UUID.randomUUID();
		UUID seatId = UUID.randomUUID();
		showtimes.save(new vn.edu.uet.cinema.business.showtime.Showtime(showtimeId, UUID.randomUUID(), UUID.randomUUID(),
			now, now.plus(2, ChronoUnit.HOURS), 100_000, vn.edu.uet.cinema.business.showtime.ShowtimeStatus.SCHEDULED, null));

		Instant expiredAt = now.minus(5, ChronoUnit.MINUTES);
		Booking b1 = new Booking(UUID.randomUUID(), UUID.randomUUID(), showtimeId, BookingStatus.PENDING,
			now.minus(15, ChronoUnit.MINUTES), expiredAt, List.of(new BookedSeat(seatId, 100_000)));
		fake.store(b1);

		DeleteOutcome outcome = singleWriter.delete(showtimeId, now);
		assertThat(outcome).isInstanceOf(DeleteOutcome.Deleted.class);

		// Model is evicted
		assertThat(singleWriter.statuses(showtimeId, now)).isEmpty();
	}
}
