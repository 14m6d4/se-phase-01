package vn.edu.uet.cinema.business.showtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
}
