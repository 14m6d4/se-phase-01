package vn.edu.uet.cinema.business.showtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShowtimeRepository {

	Showtime save(Showtime showtime);

	Optional<Showtime> findById(UUID id);

	void deleteById(UUID id);

	List<Showtime> findByStartsAtBetween(Instant fromInclusive, Instant toExclusive, UUID cinemaId, UUID movieId);

	boolean existsOverlapping(UUID auditoriumId, Instant startsAt, Instant endsAt);

	boolean existsByMovieId(UUID movieId);

	boolean existsByAuditoriumId(UUID auditoriumId);
}
