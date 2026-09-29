package vn.edu.uet.cinema.persistence.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.booking.BookedSeat;
import vn.edu.uet.cinema.business.booking.Booking;
import vn.edu.uet.cinema.business.booking.BookingRepository;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.booking.BookingStatus;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.SeatOccupancyReader;
import vn.edu.uet.cinema.business.showtime.SeatStatus;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BookingPersistenceTest {

	@Autowired BookingService bookings;
	@Autowired BookingRepository repository;
	@Autowired SeatOccupancyReader occupancy;
	@Autowired AccountService accounts;
	@Autowired MovieService movies;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;
	@Autowired ShowtimeService showtimes;

	private final Instant now = Instant.now();

	@Test
	void concurrentBookingsOfTheSameSeatHaveExactlyOneWinner() throws Exception {
		int threads = 10;
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatId = hall.seats().get(0).id();
		List<UUID> customers = IntStream.range(0, threads).mapToObj(i -> customer()).toList();
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<Boolean>> attempts = customers.stream().<Callable<Boolean>>map(customerId -> () -> {
			start.await();
			try {
				bookings.book(customerId, showtimeId, List.of(seatId), Instant.now());
				return true;
			} catch (ConflictException seatTaken) {
				return false;
			}
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
		assertThat(occupancy.statuses(showtimeId, Instant.now())).containsExactly(Map.entry(seatId, SeatStatus.HELD));
	}

	@Test
	void seatOfALapsedHoldIsFreeAndItsBookingIsDeletedWhenSomeoneElseBooksIt() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatId = hall.seats().get(0).id();
		UUID first = customer();
		Instant elevenMinutesAgo = now.minus(11, ChronoUnit.MINUTES);
		Booking lapsed = bookings.book(first, showtimeId, List.of(seatId), elevenMinutesAgo);

		assertThat(occupancy.statuses(showtimeId, now)).isEmpty();
		bookings.book(customer(), showtimeId, List.of(seatId), now);

		// Asked as of when it was still live: only a deleted Booking is missing then.
		assertThat(repository.findLivePending(first, elevenMinutesAgo)).as("lapsed Booking %s", lapsed.id())
			.isEmpty();
		assertThat(occupancy.statuses(showtimeId, now)).containsExactly(Map.entry(seatId, SeatStatus.HELD));
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

	@Test
	void seatMapShowsHeldForLiveHoldAndSoldForConfirmedBooking() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID held = hall.seats().get(0).id();
		UUID sold = hall.seats().get(1).id();
		bookings.book(customer(), showtimeId, List.of(held), now);
		repository.saveNew(new Booking(UUID.randomUUID(), customer(), showtimeId, BookingStatus.CONFIRMED,
			now.minus(1, ChronoUnit.HOURS), now.minus(50, ChronoUnit.MINUTES), List.of(new BookedSeat(sold, 100_000))),
			now);

		assertThat(occupancy.statuses(showtimeId, now))
			.containsOnly(Map.entry(held, SeatStatus.HELD), Map.entry(sold, SeatStatus.SOLD));
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
