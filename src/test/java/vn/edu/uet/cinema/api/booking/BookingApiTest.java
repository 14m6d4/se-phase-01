package vn.edu.uet.cinema.api.booking;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.auditorium.Seat;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.CancellationReason;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BookingApiTest {

	@Autowired MockMvc mvc;
	@Autowired MovieService movies;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;
	@Autowired ShowtimeService showtimes;
	@Autowired BookingService bookings;

	@Test
	void customerHoldsSeatsAndSeatMapShowsThemHeld() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		Seat a1 = hall.seats().get(0);
		Seat b1 = hall.seats().get(2);

		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + customer())
				.contentType(MediaType.APPLICATION_JSON).content(bookingJson(showtimeId, a1.id(), b1.id())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.showtimeId").value(showtimeId.toString()))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.expiresAt").exists())
			.andExpect(jsonPath("$.seats[?(@.seatId == '%s')].price", a1.id()).value(contains(100_000)))
			.andExpect(jsonPath("$.seats[?(@.seatId == '%s')].price", b1.id()).value(contains(120_000)))
			.andExpect(jsonPath("$.totalPrice").value(220_000));

		mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].status").value(contains("HELD", "AVAILABLE", "HELD", "AVAILABLE")));
	}

	@Test
	void takenSeatIsConflictAndInvalidSeatSetIsBadRequest() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatId = hall.seats().get(0).id();
		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + customer())
				.contentType(MediaType.APPLICATION_JSON).content(bookingJson(showtimeId, seatId)))
			.andExpect(status().isCreated());

		String other = customer();
		UUID freeSeatId = hall.seats().get(1).id();
		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + other)
				.contentType(MediaType.APPLICATION_JSON).content(bookingJson(showtimeId, freeSeatId, seatId)))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value(containsString("Seat")))
			.andExpect(jsonPath("$.seatIds").value(contains(seatId.toString())));
		for (String body : List.of(bookingJson(showtimeId), bookingJson(showtimeId, seatId, seatId),
			bookingJson(showtimeId, UUID.randomUUID()), "{\"showtimeId\":\"%s\"}".formatted(showtimeId))) {
			mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + other)
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
		}
	}

	@Test
	void customerPaysForAHoldAndThenItIsFinal() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatId = hall.seats().get(0).id();
		String token = customer();
		String id = hold(token, showtimeId, seatId);

		mvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.totalPrice").value(100_000));
		mvc.perform(post("/api/bookings/" + id + "/payment").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.status").value("CONFIRMED"));

		// A retried payment is safe: same Booking, still 200.
		mvc.perform(post("/api/bookings/" + id + "/payment").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
		mvc.perform(delete("/api/bookings/" + id).header("Authorization", "Bearer " + token))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
		mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
			.andExpect(jsonPath("$[0].status").value("SOLD"));
		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id").value(contains(id)))
			.andExpect(jsonPath("$[0].status").value("CONFIRMED"));
	}

	@Test
	void customerWhoPaidSeesTheBookingRefundedWhenTheShowtimeIsCancelled() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		String token = customer();
		String id = hold(token, showtimeId, hall.seats().get(0).id());
		mvc.perform(post("/api/bookings/" + id + "/payment").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk());

		showtimes.cancel(showtimeId, CancellationReason.POWER_OUTAGE, null, Instant.now());

		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].status").value("REFUNDED"))
			.andExpect(jsonPath("$[0].totalPrice").value(100_000));
		mvc.perform(delete("/api/bookings/" + id).header("Authorization", "Bearer " + token))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(containsString("REFUNDED")));
		mvc.perform(get("/api/showtimes/" + showtimeId))
			.andExpect(jsonPath("$.cancellation.reason").value("POWER_OUTAGE"));
	}

	@Test
	void holdOnACancelledShowtimeIsGoneAndTheShowtimeCannotBeBooked() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		String token = customer();
		String id = hold(token, showtimeId, hall.seats().get(0).id());

		showtimes.cancel(showtimeId, CancellationReason.EMERGENCY, "Fire alarm", Instant.now());

		mvc.perform(post("/api/bookings/" + id + "/payment").header("Authorization", "Bearer " + token))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token))
			.andExpect(jsonPath("$").isEmpty());
		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(bookingJson(showtimeId, hall.seats().get(1).id())))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void abandonedHoldIsGoneAndItsSeatsAreFree() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		String token = customer();
		String id = hold(token, showtimeId, hall.seats().get(0).id());

		mvc.perform(delete("/api/bookings/" + id).header("Authorization", "Bearer " + token))
			.andExpect(status().isNoContent());

		mvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer " + token))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
			.andExpect(jsonPath("$[0].status").value("AVAILABLE"));
	}

	@Test
	void lapsedHoldIsShownExpiredAndCannotBePaid() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		String token = customer();
		UUID customerId = UUID.fromString(JsonPath.read(mvc.perform(get("/api/me")
			.header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString(), "$.id"));
		UUID id = bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()),
			Instant.now().minus(11, ChronoUnit.MINUTES)).id();

		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token))
			.andExpect(jsonPath("$[0].status").value("EXPIRED"));
		mvc.perform(post("/api/bookings/" + id + "/payment").header("Authorization", "Bearer " + token))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(containsString("EXPIRED")));
	}

	@Test
	void someoneElsesBookingIsNotFound() throws Exception {
		Auditorium hall = auditorium();
		String id = hold(customer(), showtime(hall), hall.seats().get(0).id());
		String other = customer();

		mvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer " + other))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
		mvc.perform(post("/api/bookings/" + id + "/payment").header("Authorization", "Bearer " + other))
			.andExpect(status().isNotFound());
		mvc.perform(delete("/api/bookings/" + id).header("Authorization", "Bearer " + other))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + other))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void bookingIsForCustomersOnlyAndMarkedProtectedInOpenApi() throws Exception {
		String admin = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"admin@cinema.local\",\"password\":\"admin12345\"}"))
			.andReturn().getResponse().getContentAsString(), "$.accessToken");
		String body = bookingJson(UUID.randomUUID(), UUID.randomUUID());
		mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + admin))
			.andExpect(status().isForbidden());
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/bookings'].post.security[0].bearerAuth").exists())
			.andExpect(jsonPath("$.paths['/api/bookings'].get.security[0].bearerAuth").exists())
			.andExpect(jsonPath("$.paths['/api/bookings/{id}'].get.security[0].bearerAuth").exists())
			.andExpect(jsonPath("$.paths['/api/bookings/{id}'].delete.security[0].bearerAuth").exists())
			.andExpect(jsonPath("$.paths['/api/bookings/{id}/payment'].post.security[0].bearerAuth").exists());
	}

	@Test
	void listsCustomersBookingsWithPaginationNewestFirst() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		String token = customer();
		UUID customerId = UUID.fromString(JsonPath.read(mvc.perform(get("/api/me")
			.header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString(), "$.id"));

		Instant t1 = Instant.now().minus(5, ChronoUnit.MINUTES);
		UUID b1 = bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()), t1).id();
		bookings.pay(customerId, b1, t1);
		Instant t2 = t1.plusSeconds(30);
		UUID b2 = bookings.book(customerId, showtimeId, List.of(hall.seats().get(1).id()), t2).id();
		bookings.pay(customerId, b2, t2);
		Instant t3 = t2.plusSeconds(30);
		UUID b3 = bookings.book(customerId, showtimeId, List.of(hall.seats().get(2).id()), t3).id();

		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Total-Count", "3"))
			.andExpect(header().string("X-Page", "0"))
			.andExpect(header().string("X-Page-Size", "20"))
			.andExpect(header().string("X-Total-Pages", "1"))
			.andExpect(jsonPath("$[*].id").value(contains(b3.toString(), b2.toString(), b1.toString())));

		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token).param("page", "0").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Total-Count", "3"))
			.andExpect(header().string("X-Page", "0"))
			.andExpect(header().string("X-Page-Size", "2"))
			.andExpect(header().string("X-Total-Pages", "2"))
			.andExpect(jsonPath("$[*].id").value(contains(b3.toString(), b2.toString())));

		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token).param("page", "1").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Total-Count", "3"))
			.andExpect(header().string("X-Page", "1"))
			.andExpect(header().string("X-Page-Size", "2"))
			.andExpect(header().string("X-Total-Pages", "2"))
			.andExpect(jsonPath("$[*].id").value(contains(b1.toString())));

		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token).param("page", "2").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Total-Count", "3"))
			.andExpect(header().string("X-Page", "2"))
			.andExpect(header().string("X-Page-Size", "2"))
			.andExpect(header().string("X-Total-Pages", "2"))
			.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void rejectsInvalidPaginationParametersOnBookings() throws Exception {
		String token = customer();
		for (String[] params : List.of(
			new String[] {"page", "-1", "page"},
			new String[] {"page", "100001", "page"},
			new String[] {"size", "0", "size"},
			new String[] {"size", "101", "size"},
			new String[] {"page", "abc", "page"},
			new String[] {"size", "abc", "size"})) {
			mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token).param(params[0], params[1]))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errors[0].field").value(params[2]));
		}
	}

	private Auditorium auditorium() {
		UUID cinemaId = cinemas.create("Cinema " + UUID.randomUUID(), "Address").id();
		return auditoriums.create(cinemaId, "Hall", 2, 2, List.of("B"), List.of());
	}

	private UUID showtime(Auditorium hall) {
		UUID movieId = movies.create("Film", "Synopsis", 90, "Drama", AgeRating.P, LocalDate.parse("2026-09-01"),
			"poster").id();
		return showtimes.create(movieId, hall.id(), Instant.now().plus(1, ChronoUnit.DAYS), 100_000).id();
	}

	private String hold(String token, UUID showtimeId, UUID seatId) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(bookingJson(showtimeId, seatId)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private String customer() throws Exception {
		String email = "booking-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Customer\"}".formatted(email)))
			.andExpect(status().isCreated());
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String bookingJson(UUID showtimeId, UUID... seatIds) {
		String seats = String.join(",", Arrays.stream(seatIds).map(id -> "\"" + id + "\"").toList());
		return "{\"showtimeId\":\"%s\",\"seatIds\":[%s]}".formatted(showtimeId, seats);
	}
}
