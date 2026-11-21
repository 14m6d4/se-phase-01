package vn.edu.uet.cinema.business.showtime.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.uet.cinema.business.auditorium.InMemoryAuditoriums;
import vn.edu.uet.cinema.business.showtime.InMemoryShowtimes;

class InMemorySeatInventoryTest {

	private InMemorySeatInventory inventory;
	private final Instant now = Instant.parse("2026-11-21T12:00:00Z");

	@BeforeEach
	void setUp() {
		InMemoryShowtimes showtimes = new InMemoryShowtimes(new InMemoryAuditoriums());
		inventory = new InMemorySeatInventory(showtimes);
	}

	@Test
	void validHoldReturnsHeld() {
		UUID showtimeId = UUID.randomUUID();
		UUID customerId = UUID.randomUUID();
		UUID seatId = UUID.randomUUID();

		Booking booking = new Booking(UUID.randomUUID(), customerId, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));

		HoldOutcome outcome = inventory.hold(booking, now);

		assertThat(outcome).isInstanceOf(HoldOutcome.Held.class);
		assertThat(inventory.findById(booking.id())).contains(booking);
	}

	@Test
	void singleViolationCustomerPendingReturnsCustomerHasPending() {
		UUID showtimeId = UUID.randomUUID();
		UUID customerId = UUID.randomUUID();
		UUID seat1 = UUID.randomUUID();
		UUID seat2 = UUID.randomUUID();

		Booking first = new Booking(UUID.randomUUID(), customerId, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seat1, 100_000)));
		assertThat(inventory.hold(first, now)).isInstanceOf(HoldOutcome.Held.class);

		Booking second = new Booking(UUID.randomUUID(), customerId, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seat2, 100_000)));
		HoldOutcome outcome = inventory.hold(second, now);

		assertThat(outcome).isInstanceOf(HoldOutcome.CustomerHasPending.class);
	}

	@Test
	void singleViolationSeatTakenReturnsSeatsTaken() {
		UUID showtimeId = UUID.randomUUID();
		UUID customerA = UUID.randomUUID();
		UUID customerB = UUID.randomUUID();
		UUID seatId = UUID.randomUUID();

		Booking first = new Booking(UUID.randomUUID(), customerA, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));
		assertThat(inventory.hold(first, now)).isInstanceOf(HoldOutcome.Held.class);

		Booking second = new Booking(UUID.randomUUID(), customerB, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));
		HoldOutcome outcome = inventory.hold(second, now);

		assertThat(outcome).isInstanceOf(HoldOutcome.SeatsTaken.class);
		assertThat(((HoldOutcome.SeatsTaken) outcome).seatIds()).containsExactly(seatId);
	}

	@Test
	void whenCustomerHasPendingAndSeatsTaken_customerHasPendingTakesPrecedence() {
		UUID showtimeId = UUID.randomUUID();
		UUID customerA = UUID.randomUUID();
		UUID customerB = UUID.randomUUID();
		UUID seat1 = UUID.randomUUID();
		UUID seat2 = UUID.randomUUID();

		// Customer B holds seat1
		Booking holdB = new Booking(UUID.randomUUID(), customerB, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seat1, 100_000)));
		assertThat(inventory.hold(holdB, now)).isInstanceOf(HoldOutcome.Held.class);

		// Customer A holds seat2
		Booking holdA1 = new Booking(UUID.randomUUID(), customerA, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seat2, 100_000)));
		assertThat(inventory.hold(holdA1, now)).isInstanceOf(HoldOutcome.Held.class);

		// Customer A now attempts to hold seat1 (seat1 is taken by B, AND customer A has live pending hold A1)
		Booking holdA2 = new Booking(UUID.randomUUID(), customerA, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seat1, 100_000)));
		HoldOutcome outcome = inventory.hold(holdA2, now);

		// Must report CustomerHasPending before SeatsTaken
		assertThat(outcome).isInstanceOf(HoldOutcome.CustomerHasPending.class);
	}

	@Test
	void lapsedHoldDoesNotBlockNewHold() {
		UUID showtimeId = UUID.randomUUID();
		UUID customerA = UUID.randomUUID();
		UUID customerB = UUID.randomUUID();
		UUID seatId = UUID.randomUUID();

		// Customer A had a hold that expired 5 minutes ago
		Instant past = now.minus(15, ChronoUnit.MINUTES);
		Instant expiredAt = now.minus(5, ChronoUnit.MINUTES);
		Booking lapsed = new Booking(UUID.randomUUID(), customerA, showtimeId, BookingStatus.PENDING,
			past, expiredAt, List.of(new BookedSeat(seatId, 100_000)));
		inventory.store(lapsed);

		// Customer A can hold a new seat at 'now' because the lapsed hold is purged
		UUID seat2 = UUID.randomUUID();
		Booking freshA = new Booking(UUID.randomUUID(), customerA, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seat2, 100_000)));
		assertThat(inventory.hold(freshA, now)).isInstanceOf(HoldOutcome.Held.class);

		// Customer B can also hold the previously lapsed seatId
		Booking freshB = new Booking(UUID.randomUUID(), customerB, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));
		assertThat(inventory.hold(freshB, now)).isInstanceOf(HoldOutcome.Held.class);
	}
}
