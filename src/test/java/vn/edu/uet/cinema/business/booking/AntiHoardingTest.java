package vn.edu.uet.cinema.business.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.auditorium.InMemoryAuditoriums;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.cinema.InMemoryCinemas;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.InMemoryMovies;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.InMemoryShowtimes;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.InMemorySeatInventory;

class AntiHoardingTest {

	private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
	private final AntiHoardingGuard guard = new AntiHoardingGuard(Duration.ofSeconds(5));
	private final UUID customerId = UUID.randomUUID();

	@Test
	void newCustomerCanCreateHoldImmediately() {
		assertThatCode(() -> guard.checkHoldAllowed(customerId, NOW)).doesNotThrowAnyException();
	}

	@Test
	void abandonTriggersCooldownWindow() {
		guard.recordAbandon(customerId, NOW);

		// Within 5s cooldown: blocked
		assertThatThrownBy(() -> guard.checkHoldAllowed(customerId, NOW.plusSeconds(2)))
			.isInstanceOf(SeatHoardingException.class)
			.hasMessageContaining("cooldown");

		// After 5s cooldown: permitted
		assertThatCode(() -> guard.checkHoldAllowed(customerId, NOW.plusSeconds(6)))
			.doesNotThrowAnyException();
	}

	@Test
	void repeatedAbandonsTriggerLongerPenaltyCooldown() {
		guard.recordAbandon(customerId, NOW);
		guard.recordAbandon(customerId, NOW.plusSeconds(10));
		guard.recordAbandon(customerId, NOW.plusSeconds(20)); // 3rd abandon in window

		// Should receive 2-minute penalty
		assertThatThrownBy(() -> guard.checkHoldAllowed(customerId, NOW.plusSeconds(30)))
			.isInstanceOf(SeatHoardingException.class);

		// After 2 minutes: permitted
		assertThatCode(() -> guard.checkHoldAllowed(customerId, NOW.plusSeconds(145)))
			.doesNotThrowAnyException();
	}

	@Test
	void disabledGuardPermitsContinuousHoldsAndAbandonsWithoutCooldown() {
		AntiHoardingGuard disabled = new AntiHoardingGuard(false);
		disabled.recordAbandon(customerId, NOW);
		disabled.recordAbandon(customerId, NOW.plusSeconds(1));
		disabled.recordAbandon(customerId, NOW.plusSeconds(2));

		assertThatCode(() -> disabled.checkHoldAllowed(customerId, NOW.plusSeconds(3))).doesNotThrowAnyException();
	}

	@Test
	void multiAccountSybilHoldsFromSameClientIpAreCappedAndIsolatedAcrossClients() {
		String botIp = "198.51.100.42";
		String otherIp = "203.0.113.7";
		Instant expiresAt = NOW.plus(10, ChronoUnit.MINUTES);

		for (int i = 0; i < 3; i++) {
			UUID account = UUID.randomUUID();
			guard.checkHoldAllowed(account, botIp, NOW);
			guard.recordHoldCreated(account, botIp, UUID.randomUUID(), expiresAt, NOW);
		}

		// 4th distinct account from the same IP is blocked
		UUID fourthAccount = UUID.randomUUID();
		assertThatThrownBy(() -> guard.checkHoldAllowed(fourthAccount, botIp, NOW.plusSeconds(1)))
			.isInstanceOf(SeatHoardingException.class)
			.hasMessageContaining("maximum concurrent active holds");

		// A legitimate user from another IP is completely unaffected
		assertThatCode(() -> guard.checkHoldAllowed(UUID.randomUUID(), otherIp, NOW.plusSeconds(1)))
			.doesNotThrowAnyException();
	}

	@Test
	void releasingOrExpiringHoldFreesClientIpSlot() {
		String clientIp = "198.51.100.99";
		Instant expiresAt = NOW.plus(10, ChronoUnit.MINUTES);

		UUID b1 = UUID.randomUUID();
		UUID b2 = UUID.randomUUID();
		UUID b3 = UUID.randomUUID();
		guard.recordHoldCreated(UUID.randomUUID(), clientIp, b1, expiresAt, NOW);
		guard.recordHoldCreated(UUID.randomUUID(), clientIp, b2, expiresAt, NOW);
		guard.recordHoldCreated(UUID.randomUUID(), clientIp, b3, expiresAt, NOW);

		assertThatThrownBy(() -> guard.checkHoldAllowed(UUID.randomUUID(), clientIp, NOW.plusSeconds(5)))
			.isInstanceOf(SeatHoardingException.class);

		// Explicit release (payment or abandon) immediately unblocks the IP slot
		guard.recordHoldReleased(b1);
		assertThatCode(() -> guard.checkHoldAllowed(UUID.randomUUID(), clientIp, NOW.plusSeconds(5)))
			.doesNotThrowAnyException();

		// Refill to cap, then advance past hold expiry -> expired holds are pruned
		guard.recordHoldCreated(UUID.randomUUID(), clientIp, UUID.randomUUID(), expiresAt, NOW.plusSeconds(6));
		assertThatThrownBy(() -> guard.checkHoldAllowed(UUID.randomUUID(), clientIp, NOW.plusSeconds(7)))
			.isInstanceOf(SeatHoardingException.class);
		assertThatCode(() -> guard.checkHoldAllowed(UUID.randomUUID(), clientIp, expiresAt.plusSeconds(1)))
			.doesNotThrowAnyException();
	}

	@Test
	void boundedEvictionCapsTrackedStateMemory() {
		AntiHoardingGuard bounded = new AntiHoardingGuard(
			true, Duration.ofSeconds(5), 3, Duration.ofMinutes(2), 3, 5);
		Instant expiresAt = NOW.plus(10, ChronoUnit.MINUTES);

		for (int i = 0; i < 12; i++) {
			UUID c = UUID.randomUUID();
			bounded.recordAbandon(c, NOW.plusSeconds(i));
			bounded.recordHoldCreated(c, "10.0.0." + i, UUID.randomUUID(), expiresAt, NOW.plusSeconds(i));
		}

		assertThat(bounded.trackedCustomerCooldowns()).isLessThanOrEqualTo(5);
		assertThat(bounded.trackedAbandonHistories()).isLessThanOrEqualTo(5);
		assertThat(bounded.trackedClients()).isLessThanOrEqualTo(5);
	}

	@Test
	void bookingServiceEnforcesPerIpCapAndFreesSlotOnPayAndAbandon() {
		InMemoryAuditoriums auditoriumRows = new InMemoryAuditoriums();
		InMemoryShowtimes showtimeRows = new InMemoryShowtimes(auditoriumRows);
		InMemorySeatInventory inventory = new InMemorySeatInventory(showtimeRows);
		CinemaService cinemas = new CinemaService(new InMemoryCinemas(), auditoriumRows);
		AuditoriumService auditoriums = new AuditoriumService(cinemas, auditoriumRows, showtimeRows);
		MovieService movies = new MovieService(new InMemoryMovies(), showtimeRows);
		ShowtimeService showtimes = new ShowtimeService(movies, auditoriums, showtimeRows, inventory);
		AntiHoardingGuard configured = new AntiHoardingGuard(
			true, Duration.ofSeconds(5), 3, Duration.ofMinutes(2), 2);
		BookingService bookings = new BookingService(showtimes, inventory, inventory, configured);

		UUID cinemaId = cinemas.create("C2 Cinema", "1 Street").id();
		Auditorium hall = auditoriums.create(cinemaId, "Hall", 2, 4, List.of(), List.of());
		UUID movieId = movies.create("Movie", "Desc", 100, "Drama", AgeRating.P,
			LocalDate.parse("2026-09-01"), "poster").id();
		Showtime showtime = showtimes.create(movieId, hall.id(), NOW.plus(2, ChronoUnit.HOURS), 100_000);

		String sharedIp = "203.0.113.10";
		UUID c1 = UUID.randomUUID();
		UUID c2 = UUID.randomUUID();
		UUID c3 = UUID.randomUUID();
		UUID c4 = UUID.randomUUID();

		Booking b1 = bookings.book(c1, showtime.id(), List.of(hall.seats().get(0).id()), NOW, sharedIp);
		Booking b2 = bookings.book(c2, showtime.id(), List.of(hall.seats().get(1).id()), NOW, sharedIp);

		// 3rd customer from same IP is blocked while c1 & c2 holds are active
		assertThatThrownBy(() -> bookings.book(c3, showtime.id(), List.of(hall.seats().get(2).id()), NOW, sharedIp))
			.isInstanceOf(SeatHoardingException.class);

		// Customer from another IP succeeds
		assertThatCode(() -> bookings.book(c4, showtime.id(), List.of(hall.seats().get(3).id()), NOW, "203.0.113.11"))
			.doesNotThrowAnyException();

		// Paying b1 frees the slot for c3 on sharedIp
		bookings.pay(c1, b1.id(), NOW.plusSeconds(1));
		Booking b3 = bookings.book(c3, showtime.id(), List.of(hall.seats().get(2).id()), NOW.plusSeconds(2), sharedIp);

		// Abandoning b2 frees the IP slot for another account, while c2 enters cooldown
		bookings.abandon(c2, b2.id(), NOW.plusSeconds(3));
		assertThatThrownBy(() -> bookings.book(c2, showtime.id(), List.of(hall.seats().get(1).id()),
			NOW.plusSeconds(4), sharedIp))
			.isInstanceOf(SeatHoardingException.class)
			.hasMessageContaining("cooldown");
		assertThatCode(() -> bookings.book(UUID.randomUUID(), showtime.id(), List.of(hall.seats().get(1).id()),
			NOW.plusSeconds(4), sharedIp))
			.doesNotThrowAnyException();

		assertThat(b3.id()).isNotNull();
	}
}
