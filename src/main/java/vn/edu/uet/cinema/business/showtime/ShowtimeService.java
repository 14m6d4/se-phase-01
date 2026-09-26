package vn.edu.uet.cinema.business.showtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.movie.MovieService;

public class ShowtimeService {

	private final MovieService movies;
	private final AuditoriumService auditoriums;
	private final ShowtimeRepository showtimes;

	public ShowtimeService(MovieService movies, AuditoriumService auditoriums, ShowtimeRepository showtimes) {
		this.movies = movies;
		this.auditoriums = auditoriums;
		this.showtimes = showtimes;
	}

	public Showtime create(UUID movieId, UUID auditoriumId, Instant startsAt, long basePrice) {
		if (basePrice <= 0) {
			throw new InvalidShowtimeException("Base price must be positive");
		}
		int durationMinutes = movies.get(movieId).durationMinutes();
		auditoriums.get(auditoriumId);
		Instant endsAt = startsAt.plusSeconds(((long) durationMinutes + 15) * 60);
		if (showtimes.existsOverlapping(auditoriumId, startsAt, endsAt)) {
			throw new ConflictException("Auditorium has an overlapping Showtime");
		}
		return showtimes.save(new Showtime(UUID.randomUUID(), movieId, auditoriumId, startsAt, endsAt, basePrice));
	}

	public Showtime get(UUID id) {
		return showtimes.findById(id).orElseThrow(() -> new NotFoundException("Showtime not found"));
	}

	public List<Showtime> list(Instant fromInclusive, Instant toExclusive, UUID cinemaId, UUID movieId) {
		return showtimes.findByStartsAtBetween(fromInclusive, toExclusive, cinemaId, movieId);
	}

	public void delete(UUID id) {
		get(id);
		showtimes.deleteById(id);
	}
}
