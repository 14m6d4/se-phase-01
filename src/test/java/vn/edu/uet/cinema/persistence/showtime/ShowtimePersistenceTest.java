package vn.edu.uet.cinema.persistence.showtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ShowtimePersistenceTest {

	@Autowired ShowtimeService showtimes;
	@Autowired MovieService movies;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;

	@Test
	void savesAndFiltersByStartingTimeCinemaAndMovie() {
		UUID movieId = movies.create("Film", "Synopsis", 90, "Drama", AgeRating.P, LocalDate.parse("2026-09-01"), "poster").id();
		UUID otherMovieId = movies.create("Other", "Synopsis", 90, "Drama", AgeRating.P, LocalDate.parse("2026-09-01"), "poster").id();
		UUID cinemaId = cinemas.create("Cinema " + UUID.randomUUID(), "Address").id();
		UUID otherCinemaId = cinemas.create("Cinema " + UUID.randomUUID(), "Address").id();
		Auditorium hall = auditoriums.create(cinemaId, "Hall", 1, 1, List.of(), List.of());
		Auditorium otherHall = auditoriums.create(otherCinemaId, "Hall", 1, 1, List.of(), List.of());
		Instant start = Instant.parse("2026-10-01T17:00:00Z"); // 00:00 +07 on Oct 2
		Showtime expected = showtimes.create(movieId, hall.id(), start, 100_000);
		showtimes.create(otherMovieId, hall.id(), start.plusSeconds(10_800), 120_000);
		showtimes.create(movieId, otherHall.id(), start.plusSeconds(10_800), 100_000);

		assertThat(showtimes.get(expected.id())).isEqualTo(expected);
		assertThat(showtimes.list(start, start.plusSeconds(1), cinemaId, movieId)).containsExactly(expected);
		assertThat(showtimes.list(start.plusSeconds(1), start.plusSeconds(10_800), cinemaId, movieId)).isEmpty();
		showtimes.delete(expected.id());
		assertThat(showtimes.list(start, start.plusSeconds(1), cinemaId, movieId)).isEmpty();
	}
}
