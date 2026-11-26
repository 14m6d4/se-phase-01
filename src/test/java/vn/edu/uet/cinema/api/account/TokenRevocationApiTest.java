package vn.edu.uet.cinema.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/**
 * HTTP API integration tests for short-lived access tokens, token blacklist logout,
 * and revocable rotating refresh tokens (Issue #16 / S2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TokenRevocationApiTest {

	@Autowired
	MockMvc mvc;

	@Test
	void loginIssuesShortLivedAccessTokenAndRefreshToken() throws Exception {
		String email = registerCustomer();
		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.refreshToken").isNotEmpty())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andReturn().getResponse().getContentAsString();

		String accessToken = JsonPath.read(body, "$.accessToken");
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email));
	}

	@Test
	void logoutBlacklistsAccessTokenImmediatelyEvenAfterJwtCacheWarmup() throws Exception {
		String email = registerCustomer();
		Tokens firstSession = login(email, "password123");
		Tokens secondSession = login(email, "password123");

		// Warm up SecurityConfig's decoded-JWT cache before logout.
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + firstSession.accessToken()))
			.andExpect(status().isOk());

		// Logout without body blacklists the current access token.
		mvc.perform(post("/api/auth/logout")
				.header("Authorization", "Bearer " + firstSession.accessToken()))
			.andExpect(status().isNoContent());

		// The logged-out access token is rejected with 401 across protected, logout, and public routes.
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + firstSession.accessToken()))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists("WWW-Authenticate"))
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + firstSession.accessToken()))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + firstSession.accessToken()))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		mvc.perform(get("/api/cinemas").header("Authorization", "Bearer " + firstSession.accessToken()))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		// Another session of the same account remains valid (different jti).
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + secondSession.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email));
	}

	@Test
	void logoutRequiresAuthentication() throws Exception {
		mvc.perform(post("/api/auth/logout"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists("WWW-Authenticate"))
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void logoutWithRefreshTokenRevokesBothAccessAndRefreshTokens() throws Exception {
		String email = registerCustomer();
		Tokens tokens = login(email, "password123");

		mvc.perform(post("/api/auth/logout")
				.header("Authorization", "Bearer " + tokens.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(tokens.refreshToken())))
			.andExpect(status().isNoContent());

		mvc.perform(get("/api/me").header("Authorization", "Bearer " + tokens.accessToken()))
			.andExpect(status().isUnauthorized());

		mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(tokens.refreshToken())))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void refreshRotatesTokensAndReplayingOldRefreshTokenRevokesFamily() throws Exception {
		String email = registerCustomer();
		Tokens initial = login(email, "password123");

		String refreshedBody = mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(initial.refreshToken())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.refreshToken").isNotEmpty())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andReturn().getResponse().getContentAsString();

		String newAccess = JsonPath.read(refreshedBody, "$.accessToken");
		String newRefresh = JsonPath.read(refreshedBody, "$.refreshToken");
		assertThat(newAccess).isNotEqualTo(initial.accessToken());
		assertThat(newRefresh).isNotEqualTo(initial.refreshToken());

		mvc.perform(get("/api/me").header("Authorization", "Bearer " + newAccess))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email));

		// Replaying the rotated initial refresh token returns 401 and revokes the entire token family.
		mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(initial.refreshToken())))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		// Because reuse revoked the family, newRefresh is also rejected with 401.
		mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(newRefresh)))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void refreshValidationAndUnknownTokensReturnProblemJson() throws Exception {
		mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"completely-unknown-refresh-token\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void customerCannotRevokeAnotherCustomersRefreshTokenViaLogout() throws Exception {
		String emailA = registerCustomer();
		String emailB = registerCustomer();
		Tokens tokensA = login(emailA, "password123");
		Tokens tokensB = login(emailB, "password123");

		mvc.perform(post("/api/auth/logout")
				.header("Authorization", "Bearer " + tokensA.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(tokensB.refreshToken())))
			.andExpect(status().isNoContent());

		// Customer B's refresh token must remain active.
		mvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(tokensB.refreshToken())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void adminLogoutRevokesAdminAccessToken() throws Exception {
		Tokens admin = login("admin@cinema.local", "admin12345");

		mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + admin.accessToken()))
			.andExpect(status().isOk());

		mvc.perform(post("/api/auth/logout")
				.header("Authorization", "Bearer " + admin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(admin.refreshToken())))
			.andExpect(status().isNoContent());

		mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + admin.accessToken()))
			.andExpect(status().isUnauthorized());
	}

	private String registerCustomer() throws Exception {
		String email = "s2-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"S2 User\"}".formatted(email)))
			.andExpect(status().isCreated());
		return email;
	}

	private Tokens login(String email, String password) throws Exception {
		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
	}

	private record Tokens(String accessToken, String refreshToken) {
	}
}
