package vn.edu.uet.cinema.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vn.edu.uet.cinema.business.auditorium.Auditorium;

/**
 * End-to-End Admin Operations Test Suite (Tier 1, 2, 3).
 * Exercises showtime lifecycle, cancellation transaction semantics (ADR 0005),
 * refund tracking, and deletion constraints (ADR 0004).
 */
class AdminOperationsE2ETest extends E2ETestBase {

	@Test
	@DisplayName("Tier 1 / Tier 3: Admin cancels showtime with mixed bookings: pending deleted, confirmed refunded (ADR 0005)")
	void testCancelShowtimeDeletesPendingAndRefundsConfirmed() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 110_000);
		String adminToken = getAdminToken();

		String customerA = createCustomerToken();
		String customerB = createCustomerToken();

		// Customer A holds and pays (CONFIRMED)
		String bookingA = hold(customerA, showtimeId, hall.seats().get(0).id());
		pay(customerA, bookingA);

		// Customer B holds only (PENDING)
		String bookingB = hold(customerB, showtimeId, hall.seats().get(1).id());

		// Admin cancels showtime with equipment failure reason
		cancelShowtime(adminToken, showtimeId, "EQUIPMENT_FAILURE", "Projector bulb exploded");

		// Customer B's pending booking must be deleted (404 Not Found)
		mvc.perform(get("/api/bookings/" + bookingB).header("Authorization", "Bearer " + customerB))
			.andExpect(status().isNotFound());

		// Customer A's confirmed booking must be REFUNDED
		mvc.perform(get("/api/bookings/" + bookingA).header("Authorization", "Bearer " + customerA))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REFUNDED"))
			.andExpect(jsonPath("$.totalPrice").value(110_000));

		// Showtime details verify cancellation metadata
		mvc.perform(get("/api/showtimes/" + showtimeId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancellation.reason").value("EQUIPMENT_FAILURE"))
			.andExpect(jsonPath("$.cancellation.note").value("Projector bulb exploded"));
	}

	@Test
	@DisplayName("Tier 2: Corner Case - Attempting to hold seats on a cancelled showtime is refused with 409 Conflict")
	void testHoldOnCancelledShowtimeRefused() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String adminToken = getAdminToken();
		String customer = createCustomerToken();

		cancelShowtime(adminToken, showtimeId, "POWER_OUTAGE", null);

		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customer)
				.contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(showtimeId, hall.seats().get(0).id())))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value(containsString("Cancelled")));
	}

	@Test
	@DisplayName("Tier 2: Boundary - Deleting showtime with active bookings is rejected with 409 Conflict")
	void testDeleteShowtimeWithActiveBookingsRefused() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String adminToken = getAdminToken();
		String customer = createCustomerToken();

		// Create an active hold
		hold(customer, showtimeId, hall.seats().get(0).id());

		// Attempting DELETE /api/showtimes/{id} must fail with 409 Conflict
		mvc.perform(delete("/api/showtimes/" + showtimeId).header("Authorization", "Bearer " + adminToken))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value(containsString("Showtime has Bookings")));
	}

	@Test
	@DisplayName("Tier 2 / Tier 3: Deleting showtime with only lapsed holds purges them and succeeds with 204 No Content (ADR 0004)")
	void testDeleteShowtimeWithLapsedHoldsOnlySucceeds() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String adminToken = getAdminToken();
		String customer = createCustomerToken();

		String meResponse = mvc.perform(get("/api/me").header("Authorization", "Bearer " + customer))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		UUID customerId = UUID.fromString(JsonPath.read(meResponse, "$.id"));

		// Seed a lapsed booking (expired 20 minutes ago)
		bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()),
			Instant.now().minus(20, ChronoUnit.MINUTES));

		// ADR 0004: Lapsed holds never block a delete; they are purged lazily in the same transaction
		mvc.perform(delete("/api/showtimes/" + showtimeId).header("Authorization", "Bearer " + adminToken))
			.andExpect(status().isNoContent());

		// Verify showtime is truly deleted
		mvc.perform(get("/api/showtimes/" + showtimeId))
			.andExpect(status().isNotFound());
	}
}
