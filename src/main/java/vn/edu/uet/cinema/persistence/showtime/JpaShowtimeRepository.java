package vn.edu.uet.cinema.persistence.showtime;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;
import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasSqlState;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeRepository;
import vn.edu.uet.cinema.business.showtime.ShowtimeStatus;
import vn.edu.uet.cinema.business.showtime.inventory.Cancellation;
import vn.edu.uet.cinema.business.showtime.inventory.CancellationReason;

@Repository
class JpaShowtimeRepository implements ShowtimeRepository {

	private static final String SELECT_COLUMNS = """
		select id, movie_id, auditorium_id, starts_at, ends_at, base_price, status, cancellation_reason, cancellation_note
		from showtime
		""";
	private static final String SELECT_BY_ID = SELECT_COLUMNS + " where id = ?";
	private static final String SELECT_SCHEDULED_BETWEEN = SELECT_COLUMNS
		+ " where starts_at >= ? and starts_at < ? and status <> 'CANCELLED' order by starts_at, id";

	private final ShowtimeEntityRepository jpa;
	private final JdbcClient jdbc;

	JpaShowtimeRepository(ShowtimeEntityRepository jpa) {
		this(jpa, null);
	}

	@Autowired
	JpaShowtimeRepository(ShowtimeEntityRepository jpa, JdbcClient jdbc) {
		this.jpa = jpa;
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public Showtime save(Showtime showtime) {
		try {
			return jpa.saveAndFlush(ShowtimeEntity.from(showtime)).toShowtime();
		} catch (DataIntegrityViolationException e) {
			if (hasConstraint(e, "fk_showtime_movie")) {
				throw new ConflictException("Movie no longer exists");
			}
			if (hasConstraint(e, "fk_showtime_auditorium")) {
				throw new ConflictException("Auditorium no longer exists");
			}
			if (hasSqlState(e, "23P01")) {
				throw new ConflictException("Auditorium has an overlapping Showtime");
			}
			throw e;
		}
	}

	@Override
	public Optional<Showtime> findById(UUID id) {
		if (jdbc == null) {
			return jpa.findById(id).map(ShowtimeEntity::toShowtime);
		}
		return jdbc.sql(SELECT_BY_ID).param(id).query(JpaShowtimeRepository::mapShowtime).optional();
	}

	@Override
	public List<Showtime> findByStartsAtBetween(Instant fromInclusive, Instant toExclusive, UUID cinemaId,
		UUID movieId) {
		if (jdbc != null && cinemaId == null && movieId == null) {
			return jdbc.sql(SELECT_SCHEDULED_BETWEEN)
				.param(OffsetDateTime.ofInstant(fromInclusive, ZoneOffset.UTC))
				.param(OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC))
				.query(JpaShowtimeRepository::mapShowtime)
				.list();
		}
		return jpa.findStartingBetween(fromInclusive, toExclusive, cinemaId, movieId).stream()
			.map(ShowtimeEntity::toShowtime).toList();
	}

	private static Showtime mapShowtime(ResultSet rs, int rowNum) throws SQLException {
		String reason = rs.getString("cancellation_reason");
		Cancellation cancellation = reason == null ? null
			: new Cancellation(CancellationReason.valueOf(reason), rs.getString("cancellation_note"));
		return new Showtime(
			rs.getObject("id", UUID.class),
			rs.getObject("movie_id", UUID.class),
			rs.getObject("auditorium_id", UUID.class),
			rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
			rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
			rs.getLong("base_price"),
			ShowtimeStatus.valueOf(rs.getString("status")),
			cancellation);
	}

	@Override
	public boolean existsOverlapping(UUID auditoriumId, Instant startsAt, Instant endsAt) {
		return jpa.existsOverlapping(auditoriumId, startsAt, endsAt);
	}

	@Override
	public boolean existsByMovieId(UUID movieId) {
		return jpa.existsByMovieId(movieId);
	}

	@Override
	public boolean existsByAuditoriumId(UUID auditoriumId) {
		return jpa.existsByAuditoriumId(auditoriumId);
	}
}
