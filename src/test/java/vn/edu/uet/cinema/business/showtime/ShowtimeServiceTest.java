package vn.edu.uet.cinema.business.showtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.Movie;
import vn.edu.uet.cinema.business.movie.MovieService;

class ShowtimeServiceTest {

	private final MovieService movies = mock(MovieService.class);
	private final AuditoriumService auditoriums = mock(AuditoriumService.class);
	private final ShowtimeRepository repository = mock(ShowtimeRepository.class);
	private final ShowtimeService service = new ShowtimeService(movies, auditoriums, repository);

	@Test
	void rejectsNonPositiveBasePrice() {
		assertThatThrownBy(() -> service.create(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), 0))
			.isInstanceOf(InvalidShowtimeException.class);
	}

	@Test
	void creationSnapshotsMovieDurationPlusCleaningTime() {
		UUID movieId = UUID.randomUUID();
		UUID auditoriumId = UUID.randomUUID();
		Instant startsAt = Instant.parse("2026-10-01T14:00:00Z");
		when(movies.get(movieId)).thenReturn(new Movie(movieId, "Film", "Synopsis", 120, "Drama", AgeRating.P,
			LocalDate.parse("2026-09-01"), "poster"));
		when(auditoriums.get(auditoriumId)).thenReturn(new Auditorium(auditoriumId, UUID.randomUUID(), "Hall", 1, 1,
			java.util.List.of()));
		when(repository.save(any(Showtime.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Showtime showtime = service.create(movieId, auditoriumId, startsAt, 100_000L);

		assertThat(showtime.startsAt()).isEqualTo(startsAt);
		assertThat(showtime.endsAt()).isEqualTo(Instant.parse("2026-10-01T16:15:00Z"));
		assertThat(showtime.basePrice()).isEqualTo(100_000L);
		assertThat(showtime.movieId()).isEqualTo(movieId);
		assertThat(showtime.auditoriumId()).isEqualTo(auditoriumId);
	}

	@Test
	void overlappingShowtimeInSameAuditoriumIsRejected() {
		UUID movieId = UUID.randomUUID();
		UUID auditoriumId = UUID.randomUUID();
		Instant startsAt = Instant.parse("2026-10-01T14:00:00Z");
		when(movies.get(movieId)).thenReturn(new Movie(movieId, "Film", "Synopsis", 120, "Drama",
			AgeRating.P, LocalDate.parse("2026-09-01"), "poster"));
		when(auditoriums.get(auditoriumId)).thenReturn(new Auditorium(auditoriumId, UUID.randomUUID(), "Hall", 1, 1,
			java.util.List.of()));
		when(repository.existsOverlapping(auditoriumId, startsAt, startsAt.plusSeconds(135 * 60)))
			.thenReturn(true);

		assertThatThrownBy(() -> service.create(movieId, auditoriumId, startsAt, 100_000))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void durationArithmeticDoesNotOverflow() {
		UUID movieId = UUID.randomUUID();
		UUID auditoriumId = UUID.randomUUID();
		Instant startsAt = Instant.parse("2026-10-01T14:00:00Z");
		when(movies.get(movieId)).thenReturn(new Movie(movieId, "Film", "Synopsis", Integer.MAX_VALUE, "Drama",
			AgeRating.P, LocalDate.parse("2026-09-01"), "poster"));
		when(auditoriums.get(auditoriumId)).thenReturn(new Auditorium(auditoriumId, UUID.randomUUID(), "Hall", 1, 1,
			java.util.List.of()));
		when(repository.save(any(Showtime.class))).thenAnswer(invocation -> invocation.getArgument(0));

		assertThat(service.create(movieId, auditoriumId, startsAt, 100_000L).endsAt())
			.isEqualTo(startsAt.plusSeconds(((long) Integer.MAX_VALUE + 15) * 60));
	}

	@Test
	void cancellingReturnsTheCancelledShowtime() {
		Showtime scheduled = new Showtime(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			Instant.parse("2026-10-01T14:00:00Z"), Instant.parse("2026-10-01T16:15:00Z"), 100_000L);
		Cancellation fire = new Cancellation(CancellationReason.EMERGENCY, "Fire alarm");
		Showtime cancelled = new Showtime(scheduled.id(), scheduled.movieId(), scheduled.auditoriumId(),
			scheduled.startsAt(), scheduled.endsAt(), scheduled.basePrice(), ShowtimeStatus.CANCELLED, fire);
		Instant now = Instant.parse("2026-10-01T15:00:00Z");
		when(repository.findById(scheduled.id())).thenReturn(Optional.of(scheduled), Optional.of(cancelled));
		when(repository.cancel(scheduled.id(), fire, now)).thenReturn(true);

		assertThat(service.cancel(scheduled.id(), CancellationReason.EMERGENCY, "Fire alarm", now))
			.isEqualTo(cancelled);
		verify(repository).cancel(scheduled.id(), fire, now);
	}

	@Test
	void unknownShowtimeCannotBeCancelled() {
		UUID id = UUID.randomUUID();
		when(repository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.cancel(id, CancellationReason.EMERGENCY, null, Instant.now()))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void showtimeTheRepositoryWillNotCancelIsConflict() {
		Showtime showtime = new Showtime(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			Instant.parse("2026-10-01T14:00:00Z"), Instant.parse("2026-10-01T16:15:00Z"), 100_000L);
		when(repository.findById(showtime.id())).thenReturn(Optional.of(showtime));
		when(repository.cancel(any(), any(), any())).thenReturn(false);

		assertThatThrownBy(() -> service.cancel(showtime.id(), CancellationReason.POWER_OUTAGE, null,
			Instant.parse("2026-10-01T17:00:00Z")))
			.isInstanceOf(ConflictException.class);
	}
}
