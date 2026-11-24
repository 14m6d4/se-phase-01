package vn.edu.uet.cinema.api.booking;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.config.BookingMetrics;

@SpringBootTest(properties = {
	"app.anti-hoarding.enabled=true",
	"app.anti-hoarding.max-active-holds-per-ip=2",
	"app.anti-hoarding.abandon-cooldown-seconds=5"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AntiHoardingApiTest {

	@Autowired MockMvc mvc;
	@Autowired MovieService movies;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;
	@Autowired ShowtimeService showtimes;

	@Test
	void sameClientCannotExceedMaxActiveHoldsAcrossMultipleAccountsAndPayingFreesSlot() throws Exception {
		UUID cinemaId = cinemas.create("AH Cinema " + UUID.randomUUID(), "Address").id();
		Auditorium hall = auditoriums.create(cinemaId, "Hall", 2, 3, List.of(), List.of());
		UUID movieId = movies.create("AH Film", "Synopsis", 90, "Drama", AgeRating.P,
			LocalDate.parse("2026-09-01"), "poster").id();
		UUID showtimeId = showtimes.create(movieId, hall.id(), Instant.now().plus(1, ChronoUnit.DAYS), 100_000).id();

		String customer1 = registerAndLoginCustomer();
		String customer2 = registerAndLoginCustomer();
		String customer3 = registerAndLoginCustomer();
		String admin = loginAdmin();

		String booking1 = JsonPath.read(mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customer1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}"
					.formatted(showtimeId, hall.seats().get(0).id())))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customer2)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}"
					.formatted(showtimeId, hall.seats().get(1).id())))
			.andExpect(status().isCreated());

		// 3rd distinct account from same IP (127.0.0.1) exceeds max-active-holds-per-ip=2
		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customer3)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}"
					.formatted(showtimeId, hall.seats().get(2).id())))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail", containsString("maximum concurrent active holds")));

		// Actuator conflict counter recorded reason=seat_hoarding
		mvc.perform(get("/actuator/metrics/" + BookingMetrics.HTTP_CONFLICT_COUNTER)
				.header("Authorization", "Bearer " + admin)
				.param("tag", "reason:seat_hoarding"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());

		// Paying booking1 releases the IP hold slot so customer3 can now book
		mvc.perform(post("/api/bookings/" + booking1 + "/payment")
				.header("Authorization", "Bearer " + customer1))
			.andExpect(status().isOk());

		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customer3)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}"
					.formatted(showtimeId, hall.seats().get(2).id())))
			.andExpect(status().isCreated());
	}

	@Test
	void abandoningHoldTriggersCooldownAndReturns409ProblemJson() throws Exception {
		UUID cinemaId = cinemas.create("AH Cooldown Cinema " + UUID.randomUUID(), "Address").id();
		Auditorium hall = auditoriums.create(cinemaId, "Hall", 1, 2, List.of(), List.of());
		UUID movieId = movies.create("AH Film 2", "Synopsis", 90, "Drama", AgeRating.P,
			LocalDate.parse("2026-09-01"), "poster").id();
		UUID showtimeId = showtimes.create(movieId, hall.id(), Instant.now().plus(1, ChronoUnit.DAYS), 100_000).id();

		String customer = registerAndLoginCustomer();

		String bookingId = JsonPath.read(mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}"
					.formatted(showtimeId, hall.seats().get(0).id())))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(delete("/api/bookings/" + bookingId)
				.header("Authorization", "Bearer " + customer))
			.andExpect(status().isNoContent());

		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}"
					.formatted(showtimeId, hall.seats().get(1).id())))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail", containsString("cooldown")));
	}

	private String registerAndLoginCustomer() throws Exception {
		String email = "ah-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Customer\"}".formatted(email)))
			.andExpect(status().isCreated());
		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private String loginAdmin() throws Exception {
		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"admin@cinema.local\",\"password\":\"admin12345\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
