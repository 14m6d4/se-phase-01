package vn.edu.uet.cinema.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.uet.cinema.TestcontainersConfiguration;

@SpringBootTest(properties = "spring.profiles.active=prod")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SwaggerProdGateApiTest {

	private static final String[] DOC_ENDPOINTS = {
		"/swagger-ui.html",
		"/swagger-ui/index.html",
		"/v3/api-docs",
		"/v3/api-docs.yaml"
	};

	@Autowired MockMvc mvc;

	@Test
	void swaggerAndOpenApiDocsAreBlockedInProdProfile() throws Exception {
		for (String path : DOC_ENDPOINTS) {
			mvc.perform(get(path))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
		}

		String email = "prod-gate-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Customer\"}".formatted(email)))
			.andExpect(status().isCreated());
		String customerToken = JsonPath.read(mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(), "$.accessToken");
		String adminToken = JsonPath.read(mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"admin@cinema.local\",\"password\":\"admin12345\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(), "$.accessToken");

		for (String path : DOC_ENDPOINTS) {
			mvc.perform(get(path).header("Authorization", "Bearer " + customerToken))
				.andExpect(status().isForbidden())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
			mvc.perform(get(path).header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isForbidden())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
		}

		// Health and public API routes continue serving normally in prod profile.
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/api/movies")).andExpect(status().isOk());
	}
}
