package vn.edu.uet.cinema.business.showtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShowtimeRepository {

	Showtime save(Showtime showtime);

	Optional<Showtime> findById(UUID id);

	/** Holds that have lapsed at {@code now} are deleted along with the Showtime, in one transaction. */
	DeleteOutcome deleteById(UUID id, Instant now);

	/**
	 * Cancels the Showtime if it is Scheduled and has not ended at {@code now}. In the same transaction its Pending
	 * Bookings are deleted and its Confirmed ones become Refunded.
	 */
	CancelOutcome cancel(UUID id, Cancellation cancellation, Instant now);

	List<Showtime> findByStartsAtBetween(Instant fromInclusive, Instant toExclusive, UUID cinemaId, UUID movieId);

	boolean existsOverlapping(UUID auditoriumId, Instant startsAt, Instant endsAt);

	boolean existsByMovieId(UUID movieId);

	boolean existsByAuditoriumId(UUID auditoriumId);
}
