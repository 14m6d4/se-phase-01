package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.uet.cinema.business.showtime.inventory.AbandonOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.BookedSeat;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;
import vn.edu.uet.cinema.business.showtime.inventory.CancelOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.Cancellation;
import vn.edu.uet.cinema.business.showtime.inventory.DeleteOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.PayOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SeatStatus;
import vn.edu.uet.cinema.business.showtime.inventory.SingleWriterSeatInventory;

class BookingMetricsTest {

	@Test
	void recordsBookingTimersAndCountersWithExpectedTags() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		BookingMetrics metrics = new BookingMetrics(registry);

		String result = metrics.timeLockQuery("showtime_share", "hold", "lock_showtime", "unique", () -> "SCHEDULED");
		metrics.timeDbQuery("hold", "delete_lapsed_holds", "unique", () -> { });
		metrics.recordCommit("hold", "unique", 1_500_000L);
		metrics.recordOperation("hold", "unique", "held", 5_000_000L);
		metrics.recordDeadlockRetry("unique", "retried");
		metrics.recordConflict("seats_taken");

		assertThat(result).isEqualTo("SCHEDULED");
		assertThat(registry.get(BookingMetrics.LOCK_WAIT_TIMER)
			.tag("lock", "showtime_share").tag("strategy", "unique").timer().count()).isEqualTo(1L);
		assertThat(registry.get(BookingMetrics.DB_QUERY_TIMER)
			.tag("operation", "hold").tag("step", "lock_showtime").tag("strategy", "unique").timer().count())
			.isEqualTo(1L);
		assertThat(registry.get(BookingMetrics.DB_QUERY_TIMER)
			.tag("operation", "hold").tag("step", "delete_lapsed_holds").tag("strategy", "unique").timer().count())
			.isEqualTo(1L);
		assertThat(registry.get(BookingMetrics.DB_COMMIT_TIMER)
			.tag("operation", "hold").tag("strategy", "unique").timer().count()).isEqualTo(1L);
		assertThat(registry.get(BookingMetrics.OPERATION_TIMER)
			.tag("operation", "hold").tag("strategy", "unique").tag("outcome", "held").timer().count())
			.isEqualTo(1L);
		assertThat(registry.get(BookingMetrics.DEADLOCK_RETRY_COUNTER)
			.tag("strategy", "unique").tag("result", "retried").counter().count()).isEqualTo(1.0);
		assertThat(registry.get(BookingMetrics.HTTP_CONFLICT_COUNTER)
			.tag("reason", "seats_taken").counter().count()).isEqualTo(1.0);
	}

	@Test
	void noopMetricsSafelyExecutesSuppliersAndRunnables() {
		BookingMetrics noop = BookingMetrics.noop();
		assertThat(noop.timeDbQuery("hold", "persist_booking", "unique", () -> "ok")).isEqualTo("ok");
		assertThat(noop.timeLockQuery("showtime_share", "hold", "lock_showtime", "unique", () -> "SCHEDULED"))
			.isEqualTo("SCHEDULED");
		noop.timeDbQuery("hold", "delete_lapsed_holds", "unique", () -> { });
		noop.recordCommit("hold", "unique", 100L);
		noop.recordOperation("hold", "unique", "held", 100L);
		noop.recordDeadlockRetry("unique", "retried");
		noop.recordConflict("busy");
	}

	@Test
	void singleWriterSeatInventoryReportsLaneLockWaitToObserver() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		BookingMetrics metrics = new BookingMetrics(registry);
		Instant now = Instant.parse("2026-10-01T10:00:00Z");
		Booking booking = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			BookingStatus.PENDING, now, now.plusSeconds(600), List.of(new BookedSeat(UUID.randomUUID(), 100_000)));
		SeatInventory delegate = new StubSeatInventory(booking);

		SingleWriterSeatInventory singleWriter = new SingleWriterSeatInventory(delegate, 2,
			waitNanos -> metrics.recordLockWait("single_writer_lane", "single-writer", waitNanos));

		assertThat(singleWriter.hold(booking, now)).isEqualTo(new HoldOutcome.Held(booking));
		assertThat(registry.get(BookingMetrics.LOCK_WAIT_TIMER)
			.tag("lock", "single_writer_lane").tag("strategy", "single-writer").timer().count()).isEqualTo(1L);
	}

	private static final class StubSeatInventory implements SeatInventory {

		private final Booking booking;

		private StubSeatInventory(Booking booking) {
			this.booking = booking;
		}

		@Override
		public HoldOutcome hold(Booking pending, Instant now) {
			return new HoldOutcome.Held(booking);
		}

		@Override
		public PayOutcome pay(UUID customerId, UUID bookingId, Instant now) {
			return new PayOutcome.Gone();
		}

		@Override
		public AbandonOutcome abandon(UUID customerId, UUID bookingId) {
			return new AbandonOutcome.Gone();
		}

		@Override
		public Map<UUID, SeatStatus> statuses(UUID showtimeId, Instant now) {
			return Map.of();
		}

		@Override
		public CancelOutcome cancel(UUID showtimeId, Cancellation cancellation, Instant now) {
			return new CancelOutcome.NotFound();
		}

		@Override
		public DeleteOutcome delete(UUID showtimeId, Instant now) {
			return new DeleteOutcome.NotFound();
		}
	}
}
