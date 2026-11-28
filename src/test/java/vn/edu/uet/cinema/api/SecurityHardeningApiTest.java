package vn.edu.uet.cinema.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;

@SpringBootTest(properties = {
	"app.rate-limit.enabled=true",
	"app.rate-limit.auth.capacity=3",
	"app.rate-limit.auth.refill-tokens=3",
	"app.rate-limit.auth.refill-duration-seconds=60",
	"app.rate-limit.login.account.capacity=2",
	"app.rate-limit.login.account.refill-tokens=2",
	"app.rate-limit.login.account.refill-duration-seconds=60",
	"app.anti-hoarding.enabled=true",
	"app.anti-hoarding.max-active-holds-per-ip=1"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityHardeningApiTest {

	@Autowired MockMvc mvc;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;
	@Autowired MovieService movies;
	@Autowired ShowtimeService showtimes;

	@Test
	void xffSpoofingCannotBypassAuthRateLimit() throws Exception {
		String clientIp = "10.50.0.1";
		for (int i = 0; i < 3; i++) {
			mvc.perform(post("/api/auth/login")
					.with(remoteAddr(clientIp))
					.header("X-Forwarded-For", "198.51.100." + (i + 1))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"xff-%d-%s@example.com\",\"password\":\"wrong-pw\"}"
						.formatted(i, UUID.randomUUID())))
				.andExpect(status().isUnauthorized());
		}

		String retryAfter = mvc.perform(post("/api/auth/login")
				.with(remoteAddr(clientIp))
				.header("X-Forwarded-For", "203.0.113.99")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"xff-final-%s@example.com\",\"password\":\"wrong-pw\"}"
					.formatted(UUID.randomUUID())))
			.andExpect(status().isTooManyRequests())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(header().exists("Retry-After"))
			.andReturn().getResponse().getHeader("Retry-After");

		assertThat(Long.parseLong(retryAfter)).isGreaterThanOrEqualTo(1L);
	}

	@Test
	void loginEnforcesPerAccountLimitAcrossDistinctRemoteIps() throws Exception {
		String email = "victim-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/auth/register")
				.with(remoteAddr("10.50.1.1"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Victim\"}".formatted(email)))
			.andExpect(status().isCreated());

		// 1st login attempt succeeds and proves the JSON request body is preserved for AccountController.
		mvc.perform(post("/api/auth/login")
				.with(remoteAddr("10.50.1.2"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty());

		// 2nd login attempt from another IP consumes the remaining per-account token.
		mvc.perform(post("/api/auth/login")
				.with(remoteAddr("10.50.1.3"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"wrong-pw\"}".formatted(email.toUpperCase())))
			.andExpect(status().isUnauthorized());

		// 3rd login attempt from a third IP targeting the same normalized email is throttled with 429.
		String retryAfter = mvc.perform(post("/api/auth/login")
				.with(remoteAddr("10.50.1.4"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"  %s  \",\"password\":\"password123\"}".formatted(email)))
			.andExpect(status().isTooManyRequests())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(header().exists("Retry-After"))
			.andReturn().getResponse().getHeader("Retry-After");

		assertThat(Long.parseLong(retryAfter)).isGreaterThanOrEqualTo(1L);
	}

	@Test
	void xffSpoofingCannotBypassAntiHoardingPerIpHoldCap() throws Exception {
		UUID cinemaId = cinemas.create("S5 Cinema " + UUID.randomUUID(), "Address").id();
		Auditorium hall = auditoriums.create(cinemaId, "Hall", 1, 3, List.of(), List.of());
		UUID movieId = movies.create("S5 Film", "Synopsis", 90, "Drama", AgeRating.P,
			LocalDate.parse("2026-09-01"), "poster").id();
		UUID showtimeId = showtimes.create(movieId, hall.id(), Instant.now().plus(1, ChronoUnit.DAYS), 100_000).id();

		String customer1 = registerAndLogin("10.50.2.1");
		String customer2 = registerAndLogin("10.50.2.2");

		mvc.perform(post("/api/bookings")
				.with(remoteAddr("10.50.3.1"))
				.header("X-Forwarded-For", "198.51.100.10")
				.header("Authorization", "Bearer " + customer1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}"
					.formatted(showtimeId, hall.seats().get(0).id())))
			.andExpect(status().isCreated());

		mvc.perform(post("/api/bookings")
				.with(remoteAddr("10.50.3.1"))
				.header("X-Forwarded-For", "198.51.100.11")
				.header("Authorization", "Bearer " + customer2)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}"
					.formatted(showtimeId, hall.seats().get(1).id())))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail", containsString("maximum concurrent active holds")));
	}

	private String registerAndLogin(String clientIp) throws Exception {
		String email = "s5-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/auth/register")
				.with(remoteAddr(clientIp))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Customer\"}".formatted(email)))
			.andExpect(status().isCreated());
		String body = mvc.perform(post("/api/auth/login")
				.with(remoteAddr(clientIp))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static RequestPostProcessor remoteAddr(String ip) {
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}
}
