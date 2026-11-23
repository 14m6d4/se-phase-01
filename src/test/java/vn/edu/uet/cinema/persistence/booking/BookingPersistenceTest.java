package vn.edu.uet.cinema.persistence.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.booking.BookingRepository;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.booking.SeatsTakenException;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.business.showtime.inventory.AbandonOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.BookedSeat;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;
import vn.edu.uet.cinema.business.showtime.inventory.CancellationReason;
import vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.PayOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SeatStatus;
import vn.edu.uet.cinema.config.SeatInventoryStrategy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BookingPersistenceTest {

	@Autowired BookingService bookings;
	@Autowired BookingRepository repository;
	@Autowired SeatInventory inventory;
	@Autowired AccountService accounts;
	@Autowired MovieService movies;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;
	@Autowired ShowtimeService showtimes;
	@Autowired BookingEntityRepository jpa;
	@Autowired TransactionOperations transaction;
	@Autowired DataSource dataSource;

	private final Instant now = Instant.now();

	@Test
	void concurrentBookingsOfTheSameSeatsInAnyOrderHaveExactlyOneWinnerAndNoOtherError() throws Exception {
		int threads = 10;
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID a = hall.seats().get(0).id();
		UUID b = hall.seats().get(1).id();
		CountDownLatch start = new CountDownLatch(1);
		// Half ask for [a, b], half for [b, a]: opposite lock orders must not surface as a deadlock error.
		List<Callable<Boolean>> attempts = IntStream.range(0, threads).<Callable<Boolean>>mapToObj(i -> {
			UUID customerId = customer();
			List<UUID> seatIds = i % 2 == 0 ? List.of(a, b) : List.of(b, a);
			return () -> {
				start.await();
				try {
					bookings.book(customerId, showtimeId, seatIds, Instant.now());
					return true;
				} catch (SeatsTakenException seatsTaken) {
					assertThat(seatsTaken.seatIds()).containsExactlyElementsOf(seatIds);
					return false;
				}
			};
		}).toList();

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Boolean>> results = attempts.stream().map(pool::submit).toList();
			start.countDown();
			long winners = 0;
			for (Future<Boolean> result : results) {
				winners += result.get() ? 1 : 0;
			}
			assertThat(winners).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
		assertThat(inventory.statuses(showtimeId, Instant.now()))
			.containsOnly(Map.entry(a, SeatStatus.HELD), Map.entry(b, SeatStatus.HELD));
	}

	@Test
	void takenSeatsAreTheRequestedSeatsOfLiveHoldsAndConfirmedBookingsOnly() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID held = hall.seats().get(0).id();
		UUID sold = hall.seats().get(1).id();
		UUID lapsed = hall.seats().get(2).id();
		UUID notRequested = hall.seats().get(3).id();
		bookings.book(customer(), showtimeId, List.of(held), now);
		assertThat(inventory.hold(new Booking(UUID.randomUUID(), customer(), showtimeId, BookingStatus.CONFIRMED,
			now.minus(1, ChronoUnit.HOURS), now.minus(50, ChronoUnit.MINUTES), List.of(new BookedSeat(sold, 100_000))),
			now)).isInstanceOf(HoldOutcome.Held.class);
		bookings.book(customer(), showtimeId, List.of(lapsed), now.minus(11, ChronoUnit.MINUTES));
		bookings.book(customer(), showtimeId, List.of(notRequested), now);

		assertThat(jpa.findTakenSeats(showtimeId, List.of(held, sold, lapsed), now))
			.containsExactlyInAnyOrder(held, sold);
	}

	@Test
	void aSeatConflictNamesOnlyTheRequestedSeatsThatAreTaken() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID taken = hall.seats().get(0).id();
		UUID free = hall.seats().get(1).id();
		UUID heldButNotRequested = hall.seats().get(2).id();
		bookings.book(customer(), showtimeId, List.of(taken), now);
		bookings.book(customer(), showtimeId, List.of(heldButNotRequested), now);

		assertThatThrownBy(() -> bookings.book(customer(), showtimeId, List.of(free, taken), now))
			.isInstanceOfSatisfying(SeatsTakenException.class, e -> assertThat(e.seatIds()).containsExactly(taken));
	}

	@Test
	void seatOfALapsedHoldIsFreeAndItsBookingIsDeletedWhenSomeoneElseBooksIt() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatId = hall.seats().get(0).id();
		UUID first = customer();
		Instant elevenMinutesAgo = now.minus(11, ChronoUnit.MINUTES);
		Booking lapsed = bookings.book(first, showtimeId, List.of(seatId), elevenMinutesAgo);

		assertThat(inventory.statuses(showtimeId, now)).isEmpty();
		bookings.book(customer(), showtimeId, List.of(seatId), now);

		// Asked as of when it was still live: only a deleted Booking is missing then.
		assertThat(repository.findLivePending(first, elevenMinutesAgo)).as("lapsed Booking %s", lapsed.id())
			.isEmpty();
		assertThat(inventory.statuses(showtimeId, now)).containsExactly(Map.entry(seatId, SeatStatus.HELD));
	}

	@Test
	void customersOwnLapsedPendingBookingDoesNotStopANewOne() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID customerId = customer();
		bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()), now.minus(11, ChronoUnit.MINUTES));

		Booking fresh = bookings.book(customerId, showtimeId, List.of(hall.seats().get(1).id()), now);

		assertThat(repository.findLivePending(customerId, now)).contains(fresh);
		assertThatThrownBy(() -> bookings.book(customerId, showtimeId, List.of(hall.seats().get(2).id()), now))
			.isInstanceOf(ConflictException.class).hasMessageContaining(fresh.id().toString());
	}

	@ParameterizedTest
	@EnumSource(value = SeatInventoryStrategy.class, names = {"UNIQUE", "SHOWTIME_LOCK", "SEAT_ROWS"})
	void whenBothCustomerHasPendingAndSeatsTaken_customerHasPendingIsReported(SeatInventoryStrategy strategy) {
		JpaSeatInventory testInventory = new JpaSeatInventory(jpa, transaction, strategy);
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seat1 = hall.seats().get(0).id();
		UUID seat2 = hall.seats().get(1).id();
		UUID customerA = customer();
		UUID customerB = customer();

		// Customer B holds seat1
		Booking holdB = new Booking(UUID.randomUUID(), customerB, showtimeId, BookingStatus.PENDING,
			now, now.plusSeconds(600), List.of(new BookedSeat(seat1, 100_000)));
		assertThat(testInventory.hold(holdB, now)).isInstanceOf(HoldOutcome.Held.class);

		// Customer A holds seat2
		Booking holdA1 = new Booking(UUID.randomUUID(), customerA, showtimeId, BookingStatus.PENDING,
			now, now.plusSeconds(600), List.of(new BookedSeat(seat2, 100_000)));
		assertThat(testInventory.hold(holdA1, now)).isInstanceOf(HoldOutcome.Held.class);

		// Customer A attempts to hold seat1 (seat1 is taken by B, AND customer A has active pending hold A1)
		Booking holdA2 = new Booking(UUID.randomUUID(), customerA, showtimeId, BookingStatus.PENDING,
			now, now.plusSeconds(600), List.of(new BookedSeat(seat1, 100_000)));
		HoldOutcome outcome = testInventory.hold(holdA2, now);
		assertThat(outcome).isInstanceOf(HoldOutcome.CustomerHasPending.class);
		assertThat(((HoldOutcome.CustomerHasPending) outcome).bookingId()).isEqualTo(holdA1.id());

		// Customer A also attempts to hold their own already-held seat2
		Booking holdA3 = new Booking(UUID.randomUUID(), customerA, showtimeId, BookingStatus.PENDING,
			now, now.plusSeconds(600), List.of(new BookedSeat(seat2, 100_000)));
		assertThat(testInventory.hold(holdA3, now)).isInstanceOf(HoldOutcome.CustomerHasPending.class);
	}

	@Test
	void seatMapShowsHeldForLiveHoldAndSoldForConfirmedBooking() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID held = hall.seats().get(0).id();
		UUID sold = hall.seats().get(1).id();
		bookings.book(customer(), showtimeId, List.of(held), now);
		assertThat(inventory.hold(new Booking(UUID.randomUUID(), customer(), showtimeId, BookingStatus.CONFIRMED,
			now.minus(1, ChronoUnit.HOURS), now.minus(50, ChronoUnit.MINUTES), List.of(new BookedSeat(sold, 100_000))),
			now)).isInstanceOf(HoldOutcome.Held.class);

		assertThat(inventory.statuses(showtimeId, now))
			.containsOnly(Map.entry(held, SeatStatus.HELD), Map.entry(sold, SeatStatus.SOLD));
	}

	@Test
	void showtimeWithLiveHoldCannotBeDeletedButLapsedHoldsGoWithIt() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		bookings.book(customer(), showtimeId, List.of(hall.seats().get(0).id()), now.minus(11, ChronoUnit.MINUTES));
		bookings.book(customer(), showtimeId, List.of(hall.seats().get(1).id()), now);

		assertThatThrownBy(() -> showtimes.delete(showtimeId, now)).isInstanceOf(ConflictException.class);

		Auditorium otherHall = auditorium();
		UUID lapsedOnlyId = showtime(otherHall);
		bookings.book(customer(), lapsedOnlyId, List.of(otherHall.seats().get(0).id()), now.minus(11, ChronoUnit.MINUTES));
		showtimes.delete(lapsedOnlyId, now);
		assertThatThrownBy(() -> showtimes.get(lapsedOnlyId)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void paymentIsAConditionalUpdateThatOnlyALiveHoldPasses() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatId = hall.seats().get(0).id();
		UUID customerId = customer();
		Booking lapsed = bookings.book(customerId, showtimeId, List.of(hall.seats().get(1).id()),
			now.minus(11, ChronoUnit.MINUTES));
		assertThat(inventory.pay(lapsed, now)).isEqualTo(new PayOutcome.NotPayable(BookingStatus.EXPIRED));

		Booking live = bookings.book(customerId, showtimeId, List.of(seatId), now);

		assertThat(inventory.pay(live, now)).isEqualTo(new PayOutcome.Paid(live.confirmed()));
		assertThat(inventory.pay(live, now)).isEqualTo(new PayOutcome.AlreadyPaid(live.confirmed()));
		assertThat(inventory.pay(live.customerId(), live.id(), now)).isEqualTo(new PayOutcome.AlreadyPaid(live.confirmed()));
		assertThat(inventory.abandon(live)).isEqualTo(new AbandonOutcome.NotPending(BookingStatus.CONFIRMED));
		assertThat(inventory.abandon(live.customerId(), live.id())).isEqualTo(new AbandonOutcome.NotPending(BookingStatus.CONFIRMED));
		assertThat(repository.findById(live.id())).get().extracting(Booking::status).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(inventory.statuses(showtimeId, now)).containsExactly(Map.entry(seatId, SeatStatus.SOLD));
	}

	@Test
	void abandonedBookingIsGoneAndItsSeatsAreFree() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatId = hall.seats().get(0).id();
		Booking abandoned = bookings.book(customer(), showtimeId, List.of(seatId), now);

		assertThat(inventory.abandon(abandoned)).isEqualTo(new AbandonOutcome.Abandoned());

		assertThat(repository.findById(abandoned.id())).isEmpty();
		assertThat(inventory.statuses(showtimeId, now)).isEmpty();
		bookings.book(customer(), showtimeId, List.of(seatId), now);
	}

	@Test
	void customersBookingsAreListedNewestFirst() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID customerId = customer();
		Instant earlier = now.minus(20, ChronoUnit.MINUTES);
		Booking paid = bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()), earlier);
		inventory.pay(paid, earlier);
		Booking pending = bookings.book(customerId, showtimeId, List.of(hall.seats().get(1).id()), now);
		bookings.book(customer(), showtimeId, List.of(hall.seats().get(2).id()), now);

		assertThat(repository.findByCustomer(customerId)).extracting(Booking::id)
			.containsExactly(pending.id(), paid.id());
	}

	@Test
	void cancellingAShowtimeDeletesItsPendingBookingsIncludingExpiredOnes() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		Booking live = bookings.book(customer(), showtimeId, List.of(hall.seats().get(0).id()), now);
		Booking expired = bookings.book(customer(), showtimeId, List.of(hall.seats().get(1).id()),
			now.minus(11, ChronoUnit.MINUTES));

		showtimes.cancel(showtimeId, CancellationReason.EQUIPMENT_FAILURE, null, now);

		assertThat(repository.findById(live.id())).isEmpty();
		assertThat(repository.findById(expired.id())).isEmpty();
		assertThat(inventory.statuses(showtimeId, now)).isEmpty();
	}

	@Test
	void cancellingAShowtimeRefundsItsConfirmedBookings() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		Booking paid = bookings.book(customer(), showtimeId, List.of(hall.seats().get(0).id()), now);
		bookings.pay(paid.customerId(), paid.id(), now);

		showtimes.cancel(showtimeId, CancellationReason.POWER_OUTAGE, null, now);

		assertThat(bookings.get(paid.customerId(), paid.id()).status()).isEqualTo(BookingStatus.REFUNDED);
		assertThat(bookings.get(paid.customerId(), paid.id()).totalPrice()).isEqualTo(paid.totalPrice());
	}

	@Test
	void seatsOfARefundedBookingStaySoldWhetherOrNotItsHoldHasLapsed() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatId = hall.seats().get(0).id();
		Booking paid = bookings.book(customer(), showtimeId, List.of(seatId), now);
		bookings.pay(paid.customerId(), paid.id(), now);
		showtimes.cancel(showtimeId, CancellationReason.EQUIPMENT_FAILURE, null, now);
		Instant afterTheHold = paid.expiresAt();

		// The same Seats the unique constraint still refuses to anyone else.
		assertThat(inventory.statuses(showtimeId, now)).containsExactly(Map.entry(seatId, SeatStatus.SOLD));
		assertThat(inventory.statuses(showtimeId, afterTheHold)).containsExactly(Map.entry(seatId, SeatStatus.SOLD));
		assertThat(jpa.findTakenSeats(showtimeId, List.of(seatId), afterTheHold)).containsExactly(seatId);
	}

	@Test
	void persistenceRefusesABookingOnACancelledShowtimeEvenWhenServiceGuardWasBypassed() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		showtimes.cancel(showtimeId, CancellationReason.EMERGENCY, null, now);
		Booking late = new Booking(UUID.randomUUID(), customer(), showtimeId, BookingStatus.PENDING, now,
			now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(hall.seats().get(0).id(), 100_000)));

		assertThat(inventory.hold(late, now)).isEqualTo(new HoldOutcome.ShowtimeCancelled());
		assertThat(repository.findById(late.id())).isEmpty();
	}

	@Test
	void noPendingOrConfirmedBookingOutlivesACancelWhateverTheInterleaving() throws Exception {
		for (int round = 0; round < 5; round++) {
			UUID cinemaId = cinemas.create("Cinema " + UUID.randomUUID(), "Address").id();
			Auditorium hall = auditoriums.create(cinemaId, "Hall", 4, 4, List.of(), List.of());
			UUID showtimeId = showtime(hall);
			List<UUID> customers = hall.seats().stream().map(seat -> customer()).toList();
			CountDownLatch start = new CountDownLatch(1);
			List<Callable<Void>> work = new java.util.ArrayList<>();
			for (int i = 0; i < customers.size(); i++) {
				UUID customerId = customers.get(i);
				UUID seatId = hall.seats().get(i).id();
				work.add(() -> {
					start.await();
					try {
						Booking booking = bookings.book(customerId, showtimeId, List.of(seatId), Instant.now());
						bookings.pay(customerId, booking.id(), Instant.now());
					} catch (ConflictException | NotFoundException cancelledMeanwhile) {
						// Booking a Cancelled Showtime, or paying a Booking the cancel deleted or refunded.
					}
					return null;
				});
			}
			work.add(() -> {
				start.await();
				// Land somewhere among the bookings and payments rather than always before them.
				Thread.sleep(ThreadLocalRandom.current().nextInt(40));
				showtimes.cancel(showtimeId, CancellationReason.EMERGENCY, null, Instant.now());
				return null;
			});

			ExecutorService pool = Executors.newFixedThreadPool(work.size());
			try {
				List<Future<Void>> results = work.stream().map(pool::submit).toList();
				start.countDown();
				for (Future<Void> result : results) {
					result.get();
				}
			} finally {
				pool.shutdownNow();
			}
			List<BookingStatus> left = customers.stream().flatMap(customerId -> bookings.list(customerId).stream())
				.map(Booking::status).toList();
			assertThat(left).as("round %d", round).doesNotContain(BookingStatus.PENDING, BookingStatus.CONFIRMED);
		}
	}

	@Test
	void deadlockOnLapsedHoldRetrySucceedsWhenContentionRollsBack() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatId = hall.seats().get(0).id();
		UUID customer1 = customer();
		UUID customer2 = customer();

		// 1. Create a lapsed hold booking P
		Instant elevenMinutesAgo = now.minus(11, ChronoUnit.MINUTES);
		Booking lapsed = bookings.book(customer1, showtimeId, List.of(seatId), elevenMinutesAgo);

		// 2. Prepare fresh booking for customer2 requesting the same seat
		Booking fresh = new Booking(UUID.randomUUID(), customer2, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatId, 100_000)));

		try (Connection conn = dataSource.getConnection()) {
			conn.setAutoCommit(false);
			try (Statement s = conn.createStatement()) {
				s.execute("SET deadlock_timeout = '10s'");
			}

			// 3. Transaction T locks lapsed booking P
			try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM booking WHERE id = ? FOR UPDATE")) {
				ps.setObject(1, lapsed.id());
				try (ResultSet rs = ps.executeQuery()) {
					assertThat(rs.next()).isTrue();
				}
			}

			// 4. Launch adapter hold H in background
			CompletableFuture<HoldOutcome> holdFuture = CompletableFuture.supplyAsync(() -> inventory.hold(fresh, now));

			// 5. Wait until H is blocked on deleting P
			waitForBlockedLock(conn);

			// 6. T runs UPDATE showtime, creating deadlock cycle
			try (PreparedStatement ps = conn.prepareStatement("UPDATE showtime SET base_price = base_price WHERE id = ?")) {
				ps.setObject(1, showtimeId);
				ps.executeUpdate();
			}

			// 7. Deadlock victim (H) aborted 1st attempt and unblocked T. Now T rolls back.
			conn.rollback();

			// 8. H retries in second transaction and succeeds
			HoldOutcome outcome = holdFuture.get(10, TimeUnit.SECONDS);
			assertThat(outcome).isInstanceOf(HoldOutcome.Held.class);
		}
	}

	@Test
	void doubleDeadlockReturnsBusyOutcome() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatA = hall.seats().get(0).id();
		UUID seatB = hall.seats().get(1).id();
		UUID customer1 = customer();
		UUID customer2 = customer();
		UUID customer3 = customer();

		// 1. Create lapsed hold booking P1 on seatA and P2 on seatB
		Instant elevenMinutesAgo = now.minus(11, ChronoUnit.MINUTES);
		Booking lapsed1 = bookings.book(customer1, showtimeId, List.of(seatA), elevenMinutesAgo);
		Booking lapsed2 = bookings.book(customer2, showtimeId, List.of(seatB), elevenMinutesAgo);

		// 2. Prepare fresh booking for customer3 requesting both seats [seatA, seatB]
		Booking fresh = new Booking(UUID.randomUUID(), customer3, showtimeId, BookingStatus.PENDING,
			now, now.plus(10, ChronoUnit.MINUTES), List.of(new BookedSeat(seatA, 100_000), new BookedSeat(seatB, 100_000)));

		try (Connection conn1 = dataSource.getConnection();
			 Connection conn2 = dataSource.getConnection()) {
			conn1.setAutoCommit(false);
			conn2.setAutoCommit(false);

			try (Statement s = conn1.createStatement()) {
				s.execute("SET deadlock_timeout = '10s'");
			}
			try (Statement s = conn2.createStatement()) {
				s.execute("SET deadlock_timeout = '10s'");
			}

			// 3. Transaction T1 locks lapsed booking P1
			try (PreparedStatement ps = conn1.prepareStatement("SELECT id FROM booking WHERE id = ? FOR UPDATE")) {
				ps.setObject(1, lapsed1.id());
				try (ResultSet rs = ps.executeQuery()) {
					assertThat(rs.next()).isTrue();
				}
			}

			// 4. Transaction T2 locks lapsed booking P2
			try (PreparedStatement ps = conn2.prepareStatement("SELECT id FROM booking WHERE id = ? FOR UPDATE")) {
				ps.setObject(1, lapsed2.id());
				try (ResultSet rs = ps.executeQuery()) {
					assertThat(rs.next()).isTrue();
				}
			}

			// 5. Launch adapter hold H in background
			CompletableFuture<HoldOutcome> holdFuture = CompletableFuture.supplyAsync(() -> inventory.hold(fresh, now));

			// 6. Wait until H is blocked on deleting P1
			waitForBlockedLock(conn1);

			// 7. T1 runs UPDATE showtime, creating Deadlock #1
			try (PreparedStatement ps = conn1.prepareStatement("UPDATE showtime SET base_price = base_price WHERE id = ?")) {
				ps.setObject(1, showtimeId);
				ps.executeUpdate();
			}

			// Deadlock #1 occurs; H aborts 1st attempt. T1 rolls back, freeing P1.
			conn1.rollback();

			// 8. Wait until H retries and blocks on deleting P2 (held by T2)
			waitForBlockedLock(conn2);

			// 9. T2 runs UPDATE showtime, creating Deadlock #2
			try (PreparedStatement ps = conn2.prepareStatement("UPDATE showtime SET base_price = base_price WHERE id = ?")) {
				ps.setObject(1, showtimeId);
				ps.executeUpdate();
			}

			// Deadlock #2 occurs; H aborts 2nd attempt and returns Busy. T2 rolls back.
			conn2.rollback();

			// 10. H caught second deadlock and returned Busy
			HoldOutcome outcome = holdFuture.get(10, TimeUnit.SECONDS);
			assertThat(outcome).isInstanceOf(HoldOutcome.Busy.class);
		}
	}

	private void waitForBlockedLock(Connection conn) throws Exception {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			try (Statement s = conn.createStatement();
				 ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_locks WHERE NOT granted")) {
				if (rs.next() && rs.getInt(1) > 0) {
					return;
				}
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Timed out waiting for lock contention in pg_locks");
	}

	private UUID customer() {
		return accounts.registerCustomer("booking-" + UUID.randomUUID() + "@example.com", "password123", "Customer")
			.id();
	}

	private Auditorium auditorium() {
		UUID cinemaId = cinemas.create("Cinema " + UUID.randomUUID(), "Address").id();
		return auditoriums.create(cinemaId, "Hall", 2, 2, List.of(), List.of());
	}

	private UUID showtime(Auditorium hall) {
		UUID movieId = movies.create("Film", "Synopsis", 90, "Drama", AgeRating.P, LocalDate.parse("2026-09-01"),
			"poster").id();
		return showtimes.create(movieId, hall.id(), now.plus(1, ChronoUnit.DAYS), 100_000).id();
	}
}
