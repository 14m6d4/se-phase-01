package vn.edu.uet.cinema.api.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class AuthenticationApiTest {

	@Autowired
	MockMvc mvc;

	@Test
	void customerRegistersLogsInAndReadsOwnAccount() throws Exception {
		register("bob@example.com").andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("CUSTOMER"));
		register("BOB@example.com").andExpect(status().isConflict());

		login("bob@example.com", "wrong-password").andExpect(status().isUnauthorized());
		String token = token("bob@example.com", "password123");

		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("bob@example.com"));
	}

	@Test
	void protectedEndpointsRequireAValidToken() throws Exception {
		mvc.perform(get("/api/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists("WWW-Authenticate"))
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
		mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists("WWW-Authenticate"))
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void catalogueWritesAreAdminOnly() throws Exception {
		register("carol@example.com");
		String customer = token("carol@example.com", "password123");
		String admin = token("admin@cinema.local", "admin12345");

		mvc.perform(post("/api/cinemas").header("Authorization", "Bearer " + customer))
			.andExpect(status().isForbidden())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
		// No controller yet (issue #2), so an admin gets past security and hits 404.
		mvc.perform(post("/api/cinemas").header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());
	}

	@Test
	void metricsAreAdminOnly() throws Exception {
		register("dave@example.com");
		String customer = token("dave@example.com", "password123");
		String admin = token("admin@cinema.local", "admin12345");

		mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + customer))
			.andExpect(status().isForbidden());
		mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
	}

	@Test
	void apiDocsArePublic() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/auth/login']").exists())
			.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
	}

	@Test
	void apiDocsLockOnlyProtectedOperations() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.security").doesNotExist())
			.andExpect(jsonPath("$.paths['/api/auth/register'].post.security").doesNotExist())
			.andExpect(jsonPath("$.paths['/api/auth/login'].post.security").doesNotExist())
			.andExpect(jsonPath("$.paths['/api/me'].get.security[0].bearerAuth").exists());
	}

	@Test
	void invalidRegistrationIsAProblemDetail() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	private org.springframework.test.web.servlet.ResultActions register(String email) throws Exception {
		return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Test\"}".formatted(email)));
	}

	private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
	}

	private String token(String email, String password) throws Exception {
		String body = login(email, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
