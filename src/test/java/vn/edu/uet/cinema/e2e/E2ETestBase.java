package vn.edu.uet.cinema.e2e;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;

/**
 * Base infrastructure class for all End-to-End (E2E) opaque-box tests.
 * Connects to a real PostgreSQL database instance managed by Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class E2ETestBase {

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected MovieService movies;

	@Autowired
	protected CinemaService cinemas;

	@Autowired
	protected AuditoriumService auditoriums;

	@Autowired
	protected ShowtimeService showtimes;

	@Autowired
	protected BookingService bookings;

	/**
	 * Registers a fresh customer account and logs in, returning a valid JWT access token.
	 */
	protected String createCustomerToken() throws Exception {
		String email = "customer-" + UUID.randomUUID() + "@example.com";
		String password = "password123";
		mvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"Customer User\"}".formatted(email, password)))
			.andExpect(status().isCreated());

		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		return JsonPath.read(body, "$.accessToken");
	}

	/**
	 * Logs in with pre-configured Admin credentials and returns a valid JWT access token.
	 */
	protected String getAdminToken() throws Exception {
		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"admin@cinema.local\",\"password\":\"admin12345\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		return JsonPath.read(body, "$.accessToken");
	}

	/**
	 * Creates a test cinema and an auditorium with specified rows and columns.
	 */
	protected Auditorium createAuditorium(int rows, int cols) {
		UUID cinemaId = cinemas.create("Cinema " + UUID.randomUUID(), "Test Street 100").id();
		List<String> vipRows = rows >= 2 ? List.of("B") : List.of();
		return auditoriums.create(cinemaId, "Hall " + UUID.randomUUID(), rows, cols, vipRows, List.of());
	}

	/**
	 * Creates a test movie and schedules a showtime for the specified auditorium.
	 */
	protected UUID createShowtime(Auditorium hall, int basePrice) {
		UUID movieId = movies.create("Movie " + UUID.randomUUID(), "Synopsis", 120, "Action",
			AgeRating.P, LocalDate.parse("2026-10-01"), "poster_url").id();
		return showtimes.create(movieId, hall.id(), Instant.now().plus(2, ChronoUnit.DAYS), basePrice).id();
	}

	/**
	 * Creates a showtime with explicit start time.
	 */
	protected UUID createShowtime(Auditorium hall, Instant startsAt, int basePrice) {
		UUID movieId = movies.create("Movie " + UUID.randomUUID(), "Synopsis", 120, "Action",
			AgeRating.P, LocalDate.parse("2026-10-01"), "poster_url").id();
		return showtimes.create(movieId, hall.id(), startsAt, basePrice).id();
	}

	/**
	 * Submits a hold request via HTTP POST /api/bookings and asserts 201 Created.
	 */
	protected String hold(String token, UUID showtimeId, UUID... seatIds) throws Exception {
		MvcResult result = mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(showtimeId, seatIds)))
			.andExpect(status().isCreated())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	/**
	 * Submits a payment confirmation request via HTTP POST /api/bookings/{id}/payment.
	 */
	protected MvcResult pay(String token, String bookingId) throws Exception {
		return mvc.perform(post("/api/bookings/" + bookingId + "/payment")
				.header("Authorization", "Bearer " + token))
			.andReturn();
	}

	/**
	 * Submits a booking abandonment request via HTTP DELETE /api/bookings/{id}.
	 */
	protected MvcResult abandon(String token, String bookingId) throws Exception {
		return mvc.perform(delete("/api/bookings/" + bookingId)
				.header("Authorization", "Bearer " + token))
			.andReturn();
	}

	/**
	 * Submits a showtime cancellation request via HTTP POST /api/showtimes/{id}/cancellation.
	 */
	protected MvcResult cancelShowtime(String adminToken, UUID showtimeId, String reason, String note) throws Exception {
		String body = note == null
			? "{\"reason\":\"%s\"}".formatted(reason)
			: "{\"reason\":\"%s\",\"note\":\"%s\"}".formatted(reason, note);
		return mvc.perform(post("/api/showtimes/" + showtimeId + "/cancellation")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andReturn();
	}

	/**
	 * Builds a booking JSON request body for the specified showtime and seats.
	 */
	protected static String bookingJson(UUID showtimeId, UUID... seatIds) {
		String seats = String.join(",", Arrays.stream(seatIds).map(id -> "\"" + id + "\"").toList());
		return "{\"showtimeId\":\"%s\",\"seatIds\":[%s]}".formatted(showtimeId, seats);
	}
}
