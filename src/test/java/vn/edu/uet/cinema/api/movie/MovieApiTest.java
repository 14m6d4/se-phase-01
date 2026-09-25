package vn.edu.uet.cinema.api.movie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.uet.cinema.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MovieApiTest {

	@Autowired
	MockMvc mvc;

	@Test
	void adminCanCreateListReadUpdateAndDeleteMovie() throws Exception {
		String admin = token();
		String created = mvc.perform(post("/api/movies").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(movieJson("The First Movie", "P")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("The First Movie"))
			.andExpect(jsonPath("$.durationMinutes").value(120))
			.andExpect(jsonPath("$.ageRating").value("P"))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");

		mvc.perform(get("/api/movies"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '%s')].title".formatted(id)).value(List.of("The First Movie")));
		mvc.perform(get("/api/movies/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.description").value("A first description"));

		mvc.perform(put("/api/movies/" + id).header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(movieJson("The Updated Movie", "T13")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("The Updated Movie"))
			.andExpect(jsonPath("$.ageRating").value("T13"));

		mvc.perform(delete("/api/movies/" + id).header("Authorization", "Bearer " + admin))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/movies/" + id)).andExpect(status().isNotFound());
	}

	private String token() throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"admin@cinema.local\",\"password\":\"admin12345\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String movieJson(String title, String ageRating) {
		return "{\"title\":\"%s\",\"description\":\"A first description\",\"durationMinutes\":120,"
			+ "\"genre\":\"Drama\",\"ageRating\":\"%s\",\"releaseDate\":\"2026-01-15\","
			+ "\"posterUrl\":\"https://example.com/movie.jpg\"}".formatted(title, ageRating);
	}
}
