package vn.edu.uet.cinema.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
import vn.edu.uet.cinema.business.auditorium.Seat;

/**
 * End-to-End Booking Lifecycle Test Suite (Tier 1 & Tier 2).
 * Verifies core functional flows (happy path) and boundary/corner cases
 * across the complete booking lifecycle.
 */
class BookingLifecycleE2ETest extends E2ETestBase {

	@Test
	@DisplayName("Tier 1: Full customer booking journey: hold -> check seat map -> pay -> verify confirmed & sold")
	void testFullBookingHappyPath() throws Exception {
		Auditorium hall = createAuditorium(2, 3);
		UUID showtimeId = createShowtime(hall, 90_000);
		String token = createCustomerToken();

		Seat seatA = hall.seats().get(0);
		Seat seatB = hall.seats().get(1);

		// Step 1: Hold seats
		String bookingId = hold(token, showtimeId, seatA.id(), seatB.id());
		assertThat(bookingId).isNotNull();

		// Step 2: Verify booking status is PENDING and pricing is correct
		mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(bookingId))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.totalPrice").value(180_000));

		// Step 3: Verify seat map displays HELD for held seats and AVAILABLE for others
		mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.[?(@.id == '%s')].status", seatA.id()).value(contains("HELD")))
			.andExpect(jsonPath("$.[?(@.id == '%s')].status", seatB.id()).value(contains("HELD")))
			.andExpect(jsonPath("$.[?(@.id == '%s')].status", hall.seats().get(2).id()).value(contains("AVAILABLE")));

		// Step 4: Pay for the booking
		pay(token, bookingId);

		// Step 5: Verify booking status is now CONFIRMED
		mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"));

		// Step 6: Verify seat map reflects SOLD
		mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.[?(@.id == '%s')].status", seatA.id()).value(contains("SOLD")))
			.andExpect(jsonPath("$.[?(@.id == '%s')].status", seatB.id()).value(contains("SOLD")));
	}

	@Test
	@DisplayName("Tier 2: Single Customer constraint - Attempting a second hold while one is PENDING yields 409 Conflict")
	void testSingleCustomerPendingConstraint() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String token = createCustomerToken();

		Seat seatA = hall.seats().get(0);
		Seat seatB = hall.seats().get(1);

		// First hold succeeds
		hold(token, showtimeId, seatA.id());

		// Second hold attempt by same customer must be rejected with 409 Conflict
		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(showtimeId, seatB.id())))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value(containsString("Customer already has a Pending Booking")));
	}

	@Test
	@DisplayName("Tier 2: Multi-Seat Booking - Customer holds an entire row of seats (5 seats) in a single request")
	void testHoldMultipleSeatsMaxAuditoriumRow() throws Exception {
		Auditorium hall = createAuditorium(1, 5); // 5 seats in 1 row
		UUID showtimeId = createShowtime(hall, 100_000);
		String token = createCustomerToken();

		UUID[] seatIds = hall.seats().stream().map(Seat::id).toArray(UUID[]::new);

		String bookingId = hold(token, showtimeId, seatIds);
		assertThat(bookingId).isNotNull();

		mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.seats.length()").value(5))
			.andExpect(jsonPath("$.totalPrice").value(500_000));
	}

	@Test
	@DisplayName("Tier 2: Boundary - Hold request with empty seat array yields 400 Bad Request")
	void testEmptySeatListBadRequest() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String token = createCustomerToken();

		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[]}".formatted(showtimeId)))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	@DisplayName("Tier 2: Boundary - Duplicate seat IDs in single hold request yields 400 Bad Request")
	void testDuplicateSeatsInSingleRequestBadRequest() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String token = createCustomerToken();
		UUID seatId = hall.seats().get(0).id();

		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(showtimeId, seatId, seatId)))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	@DisplayName("Tier 2: Boundary - Payment attempt on a lapsed hold returns 409 Conflict")
	void testLapsedHoldPaymentRefused() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String token = createCustomerToken();
		String meResponse = mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		UUID customerId = UUID.fromString(JsonPath.read(meResponse, "$.id"));

		// Seed a booking that expired 12 minutes ago
		UUID bookingId = bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()),
			Instant.now().minus(12, ChronoUnit.MINUTES)).id();

		// Attempting payment on lapsed hold must yield 409 Conflict
		mvc.perform(post("/api/bookings/" + bookingId + "/payment")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(containsString("only a Pending Booking can be paid")));
	}

	@Test
	@DisplayName("Tier 2: Corner Case - Duplicate payment on already confirmed booking is idempotent and returns 200 OK")
	void testPayAlreadyConfirmedBookingIdempotent() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String token = createCustomerToken();
		String bookingId = hold(token, showtimeId, hall.seats().get(0).id());

		// First payment succeeds
		pay(token, bookingId);

		// Second payment is safe and idempotent: returns 200 OK
		mvc.perform(post("/api/bookings/" + bookingId + "/payment")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(bookingId))
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	@DisplayName("Tier 2: Corner Case - Attempting to abandon a confirmed booking yields 409 Conflict")
	void testAbandonConfirmedBookingRefused() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String token = createCustomerToken();
		String bookingId = hold(token, showtimeId, hall.seats().get(0).id());

		// Pay first
		pay(token, bookingId);

		// Attempt to delete (abandon) confirmed booking must fail with 409 Conflict
		mvc.perform(delete("/api/bookings/" + bookingId)
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value(containsString("only a Pending Booking can be abandoned")));
	}

	@Test
	@DisplayName("Tier 2: Boundary - Hold request for non-existent showtime yields 404 Not Found")
	void testNonExistentShowtimeNotFound() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		String token = createCustomerToken();
		UUID fakeShowtimeId = UUID.randomUUID();

		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(fakeShowtimeId, hall.seats().get(0).id())))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
	}
}
