package vn.edu.uet.cinema.api.cinema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
class CinemaApiTest {

	@Autowired
	MockMvc mvc;

	@Test
	void adminCanCreateReadUpdateAndDeleteCinema() throws Exception {
		String admin = token();
		String created = mvc.perform(post("/api/cinemas").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(cinemaJson("Cinema Alpha", "1 Alpha Street")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Cinema Alpha"))
			.andExpect(jsonPath("$.address").value("1 Alpha Street"))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");

		mvc.perform(get("/api/cinemas"))
			.andExpect(status().isOk())
			.andExpect(header().exists("X-Total-Count"))
			.andExpect(header().string("X-Page", "0"))
			.andExpect(header().string("X-Page-Size", "20"))
			.andExpect(header().exists("X-Total-Pages"));
		assertThat(allCinemaIds()).contains(id);
		mvc.perform(get("/api/cinemas/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Cinema Alpha"))
			.andExpect(jsonPath("$.address").value("1 Alpha Street"));

		mvc.perform(put("/api/cinemas/" + id).header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(cinemaJson("Cinema Beta", "2 Beta Street")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Cinema Beta"))
			.andExpect(jsonPath("$.address").value("2 Beta Street"));

		mvc.perform(delete("/api/cinemas/" + id).header("Authorization", "Bearer " + admin))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/cinemas/" + id)).andExpect(status().isNotFound());
	}

	@Test
	void cinemaWithAuditoriumCannotBeDeleted() throws Exception {
		String admin = token();
		String cinemaBody = mvc.perform(post("/api/cinemas").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(cinemaJson("Cinema with room " + UUID.randomUUID(), "Address")))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String cinemaId = JsonPath.read(cinemaBody, "$.id");
		mvc.perform(post("/api/cinemas/" + cinemaId + "/auditoriums").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Main\",\"rows\":1,\"seatsPerRow\":1,\"vipRows\":[],\"coupleRows\":[]}"))
			.andExpect(status().isCreated());

		mvc.perform(delete("/api/cinemas/" + cinemaId).header("Authorization", "Bearer " + admin))
			.andExpect(status().isConflict());
	}

	@Test
	void unknownCinemaWritesAreNotFound() throws Exception {
		String admin = token();
		mvc.perform(put("/api/cinemas/" + UUID.randomUUID()).header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content(cinemaJson("Missing", "Nowhere")))
			.andExpect(status().isNotFound());
		mvc.perform(delete("/api/cinemas/" + UUID.randomUUID()).header("Authorization", "Bearer " + admin))
			.andExpect(status().isNotFound());
	}

	@Test
	void listsCinemasWithPaginationAndHeaders() throws Exception {
		String admin = token();
		for (String name : List.of("000-Cinema-A", "000-Cinema-B", "000-Cinema-C")) {
			mvc.perform(post("/api/cinemas").header("Authorization", "Bearer " + admin)
					.contentType(MediaType.APPLICATION_JSON).content(cinemaJson(name, "1 Page Street")))
				.andExpect(status().isCreated());
		}

		String page0 = mvc.perform(get("/api/cinemas").param("page", "0").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Page", "0"))
			.andExpect(header().string("X-Page-Size", "2"))
			.andExpect(jsonPath("$", hasSize(2)))
			.andReturn().getResponse().getContentAsString();
		String page1 = mvc.perform(get("/api/cinemas").param("page", "1").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Page", "1"))
			.andExpect(header().string("X-Page-Size", "2"))
			.andReturn().getResponse().getContentAsString();

		List<String> ids0 = JsonPath.read(page0, "$[*].id");
		List<String> ids1 = JsonPath.read(page1, "$[*].id");
		Set<String> distinct = new HashSet<>(ids0);
		distinct.addAll(ids1);
		assertThat(distinct).hasSize(ids0.size() + ids1.size());
		List<String> names0 = JsonPath.read(page0, "$[*].name");
		assertThat(names0).isSorted();

		long total = Long.parseLong(mvc.perform(get("/api/cinemas").param("page", "9999").param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Page", "9999"))
			.andExpect(header().string("X-Page-Size", "20"))
			.andExpect(jsonPath("$", hasSize(0)))
			.andReturn().getResponse().getHeader("X-Total-Count"));
		assertThat(total).isGreaterThanOrEqualTo(3L);
	}

	@Test
	void rejectsInvalidPaginationParameters() throws Exception {
		for (String[] params : List.of(
			new String[] {"page", "-1", "page"},
			new String[] {"page", "100001", "page"},
			new String[] {"size", "0", "size"},
			new String[] {"size", "101", "size"},
			new String[] {"page", "abc", "page"},
			new String[] {"size", "abc", "size"})) {
			mvc.perform(get("/api/cinemas").param(params[0], params[1]))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errors[0].field").value(params[2]));
		}
	}

	private List<String> allCinemaIds() throws Exception {
		List<String> ids = new ArrayList<>();
		int page = 0;
		int totalPages;
		do {
			var result = mvc.perform(get("/api/cinemas").param("page", Integer.toString(page)).param("size", "100"))
				.andExpect(status().isOk()).andReturn().getResponse();
			ids.addAll(JsonPath.read(result.getContentAsString(), "$[*].id"));
			totalPages = Integer.parseInt(result.getHeader("X-Total-Pages"));
			page++;
		} while (page < totalPages);
		return ids;
	}

	private String token() throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"admin@cinema.local\",\"password\":\"admin12345\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String cinemaJson(String name, String address) {
		return "{\"name\":\"%s\",\"address\":\"%s\"}".formatted(name, address);
	}
}
