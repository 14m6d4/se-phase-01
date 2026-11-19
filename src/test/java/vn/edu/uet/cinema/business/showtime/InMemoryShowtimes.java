package vn.edu.uet.cinema.business.showtime;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.InMemoryAuditoriums;

/**
 * In-memory {@link ShowtimeRepository} for business tests. Cancelling and deleting a Showtime belong to the in-memory
 * Seat Inventory, which changes the Showtime here through {@link #replace} and {@link #remove}.
 */
public class InMemoryShowtimes implements ShowtimeRepository {

	private final Map<UUID, Showtime> rows = new LinkedHashMap<>();
	private final InMemoryAuditoriums auditoriums;

	public InMemoryShowtimes(InMemoryAuditoriums auditoriums) {
		this.auditoriums = auditoriums;
	}

	@Override
	public Showtime save(Showtime showtime) {
		rows.put(showtime.id(), showtime);
		return showtime;
	}

	@Override
	public Optional<Showtime> findById(UUID id) {
		return Optional.ofNullable(rows.get(id));
	}

	/** Test helper for the in-memory Seat Inventory: a cancel changes the Showtime in the same step. */
	public void replace(Showtime showtime) {
		if (rows.replace(showtime.id(), showtime) == null) {
			throw new IllegalStateException("No Showtime " + showtime.id());
		}
	}

	/** Test helper for the in-memory Seat Inventory: a delete removes the Showtime in the same step. */
	public void remove(UUID id) {
		rows.remove(id);
	}

	@Override
	public List<Showtime> findByStartsAtBetween(Instant fromInclusive, Instant toExclusive, UUID cinemaId,
		UUID movieId) {
		return rows.values().stream()
			.filter(s -> !s.startsAt().isBefore(fromInclusive) && s.startsAt().isBefore(toExclusive))
			.filter(s -> s.status() != ShowtimeStatus.CANCELLED)
			.filter(s -> cinemaId == null || auditoriums.findById(s.auditoriumId()).map(Auditorium::cinemaId)
				.filter(cinemaId::equals).isPresent())
			.filter(s -> movieId == null || s.movieId().equals(movieId))
			.sorted(Comparator.comparing(Showtime::startsAt).thenComparing(Showtime::id)).toList();
	}

	@Override
	public boolean existsOverlapping(UUID auditoriumId, Instant startsAt, Instant endsAt) {
		return rows.values().stream().anyMatch(s -> s.auditoriumId().equals(auditoriumId)
			&& s.startsAt().isBefore(endsAt) && s.endsAt().isAfter(startsAt) && s.status() != ShowtimeStatus.CANCELLED);
	}

	@Override
	public boolean existsByMovieId(UUID movieId) {
		return rows.values().stream().anyMatch(s -> s.movieId().equals(movieId));
	}

	@Override
	public boolean existsByAuditoriumId(UUID auditoriumId) {
		return rows.values().stream().anyMatch(s -> s.auditoriumId().equals(auditoriumId));
	}
}
