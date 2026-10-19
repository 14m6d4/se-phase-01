package vn.edu.uet.cinema.business.showtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.auditorium.InMemoryAuditoriums;
import vn.edu.uet.cinema.business.booking.BookedSeat;
import vn.edu.uet.cinema.business.booking.Booking;
import vn.edu.uet.cinema.business.booking.BookingStatus;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.cinema.InMemoryCinemas;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.InMemoryMovies;
import vn.edu.uet.cinema.business.movie.Movie;
import vn.edu.uet.cinema.business.movie.MovieService;

class ShowtimeServiceTest {

	private static final Instant STARTS_AT = Instant.parse("2026-10-01T14:00:00Z");

	private final InMemoryAuditoriums auditoriumRows = new InMemoryAuditoriums();
	private final InMemoryShowtimes repository = new InMemoryShowtimes(auditoriumRows);
	private final InMemoryMovies movieRows = new InMemoryMovies();
	private final MovieService movies = new MovieService(movieRows, repository);
	private final AuditoriumService auditoriums = new AuditoriumService(
		new CinemaService(new InMemoryCinemas(), auditoriumRows), auditoriumRows, repository);
	private final ShowtimeService service = new ShowtimeService(movies, auditoriums, repository);

	private final UUID auditoriumId = auditoriumRows.save(new Auditorium(UUID.randomUUID(), UUID.randomUUID(), "Hall",
		1, 1, List.of())).id();

	@Test
	void rejectsNonPositiveBasePrice() {
		assertThatThrownBy(() -> service.create(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), 0))
			.isInstanceOf(InvalidShowtimeException.class);
	}

	@Test
	void creationSnapshotsMovieDurationPlusCleaningTime() {
		UUID movieId = movie(120);

		Showtime showtime = service.create(movieId, auditoriumId, STARTS_AT, 100_000L);

		assertThat(showtime.startsAt()).isEqualTo(STARTS_AT);
		assertThat(showtime.endsAt()).isEqualTo(Instant.parse("2026-10-01T16:15:00Z"));
		assertThat(showtime.basePrice()).isEqualTo(100_000L);
		assertThat(showtime.movieId()).isEqualTo(movieId);
		assertThat(showtime.auditoriumId()).isEqualTo(auditoriumId);
		assertThat(service.get(showtime.id())).isEqualTo(showtime);
	}

	@Test
	void overlappingShowtimeInSameAuditoriumIsRejected() {
		UUID movieId = movie(120);
		service.create(movieId, auditoriumId, STARTS_AT, 100_000);

		assertThatThrownBy(() -> service.create(movieId, auditoriumId, STARTS_AT.plusSeconds(134 * 60), 100_000))
			.isInstanceOf(ConflictException.class);
		service.create(movieId, auditoriumId, STARTS_AT.plusSeconds(135 * 60), 100_000);
	}

	@Test
	void durationArithmeticDoesNotOverflow() {
		UUID movieId = movie(Integer.MAX_VALUE);

		assertThat(service.create(movieId, auditoriumId, STARTS_AT, 100_000L).endsAt())
			.isEqualTo(STARTS_AT.plusSeconds(((long) Integer.MAX_VALUE + 15) * 60));
	}

	@Test
	void cancellingReturnsTheCancelledShowtimeAndSettlesItsBookings() {
		Showtime scheduled = scheduled();
		Instant now = Instant.parse("2026-10-01T15:00:00Z");
		Booking pending = booking(scheduled, BookingStatus.PENDING, now.plusSeconds(60));
		Booking confirmed = booking(scheduled, BookingStatus.CONFIRMED, now.minusSeconds(60));

		Showtime cancelled = service.cancel(scheduled.id(), CancellationReason.EMERGENCY, "Fire alarm", now);

		assertThat(cancelled).isEqualTo(new Showtime(scheduled.id(), scheduled.movieId(), scheduled.auditoriumId(),
			scheduled.startsAt(), scheduled.endsAt(), scheduled.basePrice(), ShowtimeStatus.CANCELLED,
			new Cancellation(CancellationReason.EMERGENCY, "Fire alarm")));
		assertThat(service.get(scheduled.id())).isEqualTo(cancelled);
		assertThat(repository.bookingRows).doesNotContainKey(pending.id());
		assertThat(repository.bookingRows.get(confirmed.id()).status()).isEqualTo(BookingStatus.REFUNDED);
	}

	@Test
	void unknownShowtimeCannotBeCancelled() {
		assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), CancellationReason.EMERGENCY, null, Instant.now()))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void endedOrAlreadyCancelledShowtimeCannotBeCancelled() {
		Showtime ended = scheduled();
		Showtime twice = scheduled();
		Instant now = Instant.parse("2026-10-01T13:00:00Z");
		service.cancel(twice.id(), CancellationReason.EMERGENCY, null, now);

		assertThatThrownBy(() -> service.cancel(ended.id(), CancellationReason.POWER_OUTAGE, null, ended.endsAt()))
			.isInstanceOf(ConflictException.class);
		assertThatThrownBy(() -> service.cancel(twice.id(), CancellationReason.POWER_OUTAGE, null, now))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void otherNeedsANoteAndABlankNoteIsNoNote() {
		Showtime showtime = scheduled();
		Instant now = Instant.parse("2026-10-01T13:00:00Z");

		for (String missing : new String[] {null, "", "   "}) {
			assertThatThrownBy(() -> service.cancel(showtime.id(), CancellationReason.OTHER, missing, now))
				.as("note %s", missing).isInstanceOf(InvalidShowtimeException.class);
		}
		assertThat(service.cancel(showtime.id(), CancellationReason.EQUIPMENT_FAILURE, "  ", now).cancellation())
			.isEqualTo(new Cancellation(CancellationReason.EQUIPMENT_FAILURE, null));
	}

	@Test
	void noteIsAtMostTwoHundredCharacters() {
		Showtime showtime = scheduled();
		Instant now = Instant.parse("2026-10-01T13:00:00Z");

		assertThatThrownBy(() -> service.cancel(showtime.id(), CancellationReason.OTHER, "x".repeat(201), now))
			.isInstanceOf(InvalidShowtimeException.class);
		assertThat(service.cancel(showtime.id(), CancellationReason.OTHER, "x".repeat(200), now).cancellation().note())
			.hasSize(200);
	}

	@Test
	void deletingAtAFixedTimeRemovesLapsedHoldsButALiveHoldBlocks() {
		Instant now = Instant.parse("2026-10-01T13:00:00Z");
		Showtime lapsedOnly = scheduled();
		Booking lapsed = booking(lapsedOnly, BookingStatus.PENDING, now.minusSeconds(1));
		Showtime held = scheduled();
		booking(held, BookingStatus.PENDING, now.plusSeconds(1));

		service.delete(lapsedOnly.id(), now);

		assertThatThrownBy(() -> service.get(lapsedOnly.id())).isInstanceOf(NotFoundException.class);
		assertThat(repository.bookingRows).doesNotContainKey(lapsed.id());
		assertThatThrownBy(() -> service.delete(held.id(), now)).isInstanceOf(ConflictException.class);
		assertThat(service.get(held.id())).isEqualTo(held);
	}

	private UUID movie(int durationMinutes) {
		return movieRows.save(new Movie(UUID.randomUUID(), "Film", "Synopsis", durationMinutes, "Drama", AgeRating.P,
			LocalDate.parse("2026-09-01"), "poster")).id();
	}

	private Showtime scheduled() {
		return repository.save(new Showtime(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), STARTS_AT,
			Instant.parse("2026-10-01T16:15:00Z"), 100_000L));
	}

	private Booking booking(Showtime showtime, BookingStatus status, Instant expiresAt) {
		Booking booking = new Booking(UUID.randomUUID(), UUID.randomUUID(), showtime.id(), status,
			expiresAt.minusSeconds(600), expiresAt, List.of(new BookedSeat(UUID.randomUUID(), 100_000)));
		repository.bookingRows.put(booking.id(), booking);
		return booking;
	}
}
