package vn.edu.uet.cinema.persistence.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IndexQueryPlanTest {

	@Autowired JdbcClient jdbc;
	@Autowired TransactionOperations transaction;
	@Autowired AccountService accounts;
	@Autowired CinemaService cinemas;
	@Autowired MovieService movies;
	@Autowired AuditoriumService auditoriums;
	@Autowired ShowtimeService showtimes;
	@Autowired BookingService bookings;

	@Test
	void pgIndexesContainOptimizedV12IndexesAndNoRedundantIndexes() {
		List<String> indexNames = jdbc.sql("""
			SELECT indexname FROM pg_indexes
			WHERE schemaname = current_schema()
			  AND tablename IN ('booking', 'booked_seat', 'showtime')
			""")
			.query(String.class)
			.list();

		assertThat(indexNames)
			.doesNotContain("ix_booked_seat_booking_id", "ix_booking_pending_expires_at",
				"ix_booking_customer_id", "ix_booking_showtime_id")
			.contains("ix_booking_customer_created_desc", "ix_booking_showtime_status_expires",
				"ix_showtime_scheduled_starts_at", "ix_showtime_movie_id");
	}

	@Test
	void hotPathQueriesUseExpectedIndexesInPostgreSqlPlans() {
		UUID cinemaId = cinemas.create("Plan Cinema " + UUID.randomUUID(), "Address").id();
		Auditorium hall = auditoriums.create(cinemaId, "Plan Hall", 2, 2, List.of(), List.of());
		UUID movieId = movies.create("Plan Movie", "Synopsis", 90, "Drama", AgeRating.P,
			LocalDate.parse("2026-09-01"), "poster").id();
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		UUID showtimeId = showtimes.create(movieId, hall.id(), now.plus(1, ChronoUnit.DAYS), 100_000).id();
		UUID seatId = hall.seats().get(0).id();
		UUID customerId = accounts.registerCustomer("plan-" + UUID.randomUUID() + "@example.com",
			"password123", "Plan User").id();
		bookings.book(customerId, showtimeId, List.of(seatId), now);

		OffsetDateTime from = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		OffsetDateTime to = OffsetDateTime.ofInstant(now.plus(2, ChronoUnit.DAYS), ZoneOffset.UTC);

		transaction.executeWithoutResult(status -> {
			jdbc.sql("SET LOCAL enable_seqscan = off").update();

			String takenSeatsPlan = explain("""
				EXPLAIN (FORMAT TEXT)
				SELECT s.seat_id FROM booked_seat s
				JOIN booking b ON b.id = s.booking_id
				WHERE s.showtime_id = ? AND s.seat_id IN (?)
				  AND (b.status IN ('CONFIRMED', 'REFUNDED') OR (b.status = 'PENDING' AND b.expires_at > ?))
				""", showtimeId, seatId, from);
			assertThat(takenSeatsPlan).contains("uq_booked_seat_showtime_seat");

			String occupiedSeatsPlan = explain("""
				EXPLAIN (FORMAT TEXT)
				SELECT s.seat_id, b.status FROM booked_seat s
				JOIN booking b ON b.id = s.booking_id
				WHERE s.showtime_id = ?
				  AND (b.status IN ('CONFIRMED', 'REFUNDED') OR (b.status = 'PENDING' AND b.expires_at > ?))
				""", showtimeId, from);
			assertThat(occupiedSeatsPlan).contains("uq_booked_seat_showtime_seat");

			String customerBookingsPlan = explain("""
				EXPLAIN (FORMAT TEXT)
				SELECT id, customer_id, showtime_id, status, created_at, expires_at
				FROM booking
				WHERE customer_id = ?
				ORDER BY created_at DESC, id DESC
				LIMIT 20
				""", customerId);
			assertThat(customerBookingsPlan).contains("ix_booking_customer_created_desc");

			String showtimeLapsedPlan = explain("""
				EXPLAIN (FORMAT TEXT)
				SELECT id FROM booking b
				WHERE b.showtime_id = ? AND b.status = 'PENDING' AND b.expires_at <= ?
				""", showtimeId, from);
			assertThat(showtimeLapsedPlan).contains("ix_booking_showtime_status_expires");

			String browseShowtimesPlan = explain("""
				EXPLAIN (FORMAT TEXT)
				SELECT id, movie_id, auditorium_id, starts_at, ends_at, base_price, status
				FROM showtime
				WHERE starts_at >= ? AND starts_at < ? AND status <> 'CANCELLED'
				ORDER BY starts_at, id
				""", from, to);
			assertThat(browseShowtimesPlan).contains("ix_showtime_scheduled_starts_at");
		});
	}

	private String explain(String sql, Object... params) {
		var spec = jdbc.sql(sql);
		for (Object param : params) {
			spec = spec.param(param);
		}
		return String.join("\n", spec.query(String.class).list());
	}
}
