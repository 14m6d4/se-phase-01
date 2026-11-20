package vn.edu.uet.cinema.e2e;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vn.edu.uet.cinema.business.auditorium.Auditorium;

/**
 * End-to-End Security, RBAC & Validation Test Suite (Tier 1 & Tier 2).
 * Exercises authentication, role-based authorization, boundary validations,
 * problem+json conformance, and NUL character protection (B8 regression defense).
 */
class SecurityAndValidationE2ETest extends E2ETestBase {

	@Test
	@DisplayName("Tier 1: Unauthenticated requests to protected endpoints are rejected with 401 Unauthorized")
	void testUnauthenticatedAccessRejected() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);

		// Post booking without token -> 401
		mvc.perform(post("/api/bookings")
				.contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(showtimeId, hall.seats().get(0).id())))
			.andExpect(status().isUnauthorized());

		// Get bookings without token -> 401
		mvc.perform(get("/api/bookings"))
			.andExpect(status().isUnauthorized());

		// Cancel showtime without token -> 401
		mvc.perform(post("/api/showtimes/" + showtimeId + "/cancellation")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"EQUIPMENT_FAILURE\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Tier 1 / Tier 2: Customer role attempting Admin-only endpoints is rejected with 403 Forbidden")
	void testCustomerAccessingAdminEndpointsForbidden() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String customerToken = createCustomerToken();

		// Customer attempts to cancel showtime -> 403 Forbidden
		mvc.perform(post("/api/showtimes/" + showtimeId + "/cancellation")
				.header("Authorization", "Bearer " + customerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"EQUIPMENT_FAILURE\"}"))
			.andExpect(status().isForbidden());

		// Customer attempts to delete showtime -> 403 Forbidden
		mvc.perform(delete("/api/showtimes/" + showtimeId)
				.header("Authorization", "Bearer " + customerToken))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Tier 1 / Tier 2: Admin role attempting Customer booking endpoints is rejected with 403 Forbidden")
	void testAdminAccessingCustomerEndpointsForbidden() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String adminToken = getAdminToken();

		// Admin attempts to create booking -> 403 Forbidden
		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(showtimeId, hall.seats().get(0).id())))
			.andExpect(status().isForbidden());

		// Admin attempts to view customer booking list -> 403 Forbidden
		mvc.perform(get("/api/bookings")
				.header("Authorization", "Bearer " + adminToken))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Tier 2: Customer A cannot view, pay, or delete Customer B's booking (returns 404 Not Found)")
	void testCustomerCannotAccessOtherCustomerBooking() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);

		String customerA = createCustomerToken();
		String customerB = createCustomerToken();

		// Customer A creates booking
		String bookingA = hold(customerA, showtimeId, hall.seats().get(0).id());

		// Customer B attempts GET /api/bookings/{id} -> 404
		mvc.perform(get("/api/bookings/" + bookingA).header("Authorization", "Bearer " + customerB))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		// Customer B attempts POST /api/bookings/{id}/payment -> 404
		mvc.perform(post("/api/bookings/" + bookingA + "/payment").header("Authorization", "Bearer " + customerB))
			.andExpect(status().isNotFound());

		// Customer B attempts DELETE /api/bookings/{id} -> 404
		mvc.perform(delete("/api/bookings/" + bookingA).header("Authorization", "Bearer " + customerB))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Tier 2: Adversarial - NUL character in request payload is refused with 400 Bad Request, never 500 (B8 regression)")
	void testNulCharacterInRequestPayloadRefused() throws Exception {
		String adminToken = getAdminToken();

		// Send movie creation request containing NUL character \u0000
		String maliciousJson = "{\"title\":\"Movie\\u0000Title\",\"synopsis\":\"Synopsis\",\"durationMinutes\":120,\"genre\":\"Action\",\"ageRating\":\"P\",\"releaseDate\":\"2026-10-01\",\"posterUrl\":\"url\"}";

		mvc.perform(post("/api/movies")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(maliciousJson))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	@DisplayName("Tier 2: Malformed JSON syntax in booking payload returns 400 Bad Request with RFC 7807 problem details")
	void testMalformedJsonPayloadReturnsBadRequest() throws Exception {
		String customerToken = createCustomerToken();

		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\": \"not-a-valid-json-closing"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	@DisplayName("Tier 1: OpenAPI specification validates security requirements on all booking and admin endpoints")
	void testOpenApiSecuritySpecificationCompliance() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/bookings'].post.security[0].bearerAuth").exists())
			.andExpect(jsonPath("$.paths['/api/bookings'].get.security[0].bearerAuth").exists())
			.andExpect(jsonPath("$.paths['/api/bookings/{id}'].get.security[0].bearerAuth").exists())
			.andExpect(jsonPath("$.paths['/api/bookings/{id}'].delete.security[0].bearerAuth").exists())
			.andExpect(jsonPath("$.paths['/api/bookings/{id}/payment'].post.security[0].bearerAuth").exists());
	}
}
