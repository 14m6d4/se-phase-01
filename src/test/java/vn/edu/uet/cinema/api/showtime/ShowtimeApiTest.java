package vn.edu.uet.cinema.api.showtime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
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
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ShowtimeApiTest {

	@Autowired MockMvc mvc;
	@Autowired MovieService movies;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;

	@Test
	void adminSchedulesAndDeletesShowtimeWithOffsetConvertedToInstant() throws Exception {
		String admin = token("admin@cinema.local", "admin12345");
		UUID movieId = movieId();
		UUID auditoriumId = auditoriumId();
		String created = mvc.perform(post("/api/showtimes").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(showtimeJson(movieId, auditoriumId, "2026-09-27T20:00:00+07:00", 100_000)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.movieId").value(movieId.toString()))
			.andExpect(jsonPath("$.auditoriumId").value(auditoriumId.toString()))
			.andExpect(jsonPath("$.startsAt").value("2026-09-27T13:00:00Z"))
			.andExpect(jsonPath("$.basePrice").value(100_000))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");

		mvc.perform(delete("/api/showtimes/" + id).header("Authorization", "Bearer " + admin))
			.andExpect(status().isNoContent());
	}

	@Test
	void showtimeWritesRequireAdmin() throws Exception {
		String body = showtimeJson(UUID.randomUUID(), UUID.randomUUID(), "2026-09-27T20:00:00+07:00", 100_000);
		mvc.perform(post("/api/showtimes").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		String email = "showtime-customer-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Customer\"}".formatted(email)))
			.andExpect(status().isCreated());
		String customer = token(email, "password123");
		mvc.perform(post("/api/showtimes").header("Authorization", "Bearer " + customer)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isForbidden())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void invalidShowtimeRequestIsProblemDetail() throws Exception {
		String admin = token("admin@cinema.local", "admin12345");
		mvc.perform(post("/api/showtimes").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(showtimeJson(UUID.randomUUID(), UUID.randomUUID(), "2026-09-27T20:00:00+07:00", 0)))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void showtimeWriteOperationsAreMarkedProtectedInOpenApi() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/showtimes'].post.security[0].bearerAuth").exists())
			.andExpect(jsonPath("$.paths['/api/showtimes/{id}'].delete.security[0].bearerAuth").exists());
	}

	private UUID movieId() {
		return movies.create("Film " + UUID.randomUUID(), "Synopsis", 120, "Drama", AgeRating.P,
			LocalDate.parse("2026-09-01"), "poster").id();
	}

	private UUID auditoriumId() {
		UUID cinemaId = cinemas.create("Cinema " + UUID.randomUUID(), "Address").id();
		return auditoriums.create(cinemaId, "Hall", 1, 1, List.of(), List.of()).id();
	}

	private String token(String email, String password) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String showtimeJson(UUID movieId, UUID auditoriumId, String startsAt, long basePrice) {
		return "{\"movieId\":\"%s\",\"auditoriumId\":\"%s\",\"startsAt\":\"%s\",\"basePrice\":%d}"
			.formatted(movieId, auditoriumId, startsAt, basePrice);
	}
}
