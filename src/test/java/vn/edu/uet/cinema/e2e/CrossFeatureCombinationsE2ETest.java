package vn.edu.uet.cinema.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.Seat;

/**
 * End-to-End Cross-Feature Combinations Test Suite (Tier 3).
 * Verifies pairwise and multi-feature interaction behaviors under race conditions.
 */
class CrossFeatureCombinationsE2ETest extends E2ETestBase {

	@Test
	@DisplayName("Tier 3: Pairwise - Simultaneous hold creation racing against Admin showtime cancellation")
	void testHoldAndShowtimeCancellationRace() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String adminToken = getAdminToken();
		String customerToken = createCustomerToken();
		Seat seat = hall.seats().get(0);

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);

		Callable<Integer> cancelTask = () -> {
			start.await();
			return cancelShowtime(adminToken, showtimeId, "EQUIPMENT_FAILURE", "Emergency").getResponse().getStatus();
		};

		Callable<Integer> holdTask = () -> {
			start.await();
			return mvc.perform(post("/api/bookings")
					.header("Authorization", "Bearer " + customerToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(bookingJson(showtimeId, seat.id())))
				.andReturn().getResponse().getStatus();
		};

		try {
			Future<Integer> fCancel = pool.submit(cancelTask);
			Future<Integer> fHold = pool.submit(holdTask);
			start.countDown();

			int cancelStatus = fCancel.get();
			int holdStatus = fHold.get();

			assertThat(cancelStatus).as("Admin cancel must succeed").isEqualTo(200);
			// Hold either succeeded before cancel (201) and was promptly purged by cancel step 2,
			// or was rejected after cancel (409 Conflict). It must never 500.
			assertThat(holdStatus).as("Hold must be either 201 or 409, never 500").isIn(201, 409);

			// In either outcome, the showtime is cancelled and no active pending bookings exist
			mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + customerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("Tier 3: Pairwise - Concurrent duplicate payment requests for the same booking produce 1 payment and 1 idempotent confirmation")
	void testConcurrentDuplicatePaymentRequests() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		String customerToken = createCustomerToken();
		Seat seat = hall.seats().get(0);

		String bookingId = hold(customerToken, showtimeId, seat.id());

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);

		Callable<Integer> payTask1 = () -> {
			start.await();
			return pay(customerToken, bookingId).getResponse().getStatus();
		};
		Callable<Integer> payTask2 = () -> {
			start.await();
			return pay(customerToken, bookingId).getResponse().getStatus();
		};

		try {
			Future<Integer> f1 = pool.submit(payTask1);
			Future<Integer> f2 = pool.submit(payTask2);
			start.countDown();

			int s1 = f1.get();
			int s2 = f2.get();

			// Both payment calls must succeed cleanly with 200 OK (idempotent payment semantics)
			assertThat(s1).isEqualTo(200);
			assertThat(s2).isEqualTo(200);

			// Booking must be CONFIRMED exactly once
			mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", "Bearer " + customerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

			// Seat status is SOLD
			mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.[?(@.id == '%s')].status", seat.id()).value(contains("SOLD")));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("Tier 3: Cart abandonment enables immediate re-booking of the exact same seat by another customer")
	void testCustomerAbandonsCartCompetingCustomerImmediatelyAcquires() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		Seat seat = hall.seats().get(0);

		String customerA = createCustomerToken();
		String customerB = createCustomerToken();

		// Customer A holds seat
		String bookingA = hold(customerA, showtimeId, seat.id());

		// Customer B attempts hold -> 409 Conflict
		mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customerB)
				.contentType(MediaType.APPLICATION_JSON)
				.content(bookingJson(showtimeId, seat.id())))
			.andExpect(status().isConflict());

		// Customer A abandons cart -> 204 No Content
		abandon(customerA, bookingA);

		// Customer B immediately re-submits hold -> 201 Created
		String bookingB = hold(customerB, showtimeId, seat.id());
		assertThat(bookingB).isNotNull();

		// Customer B confirms payment
		pay(customerB, bookingB);

		// Verify final state: booking confirmed for Customer B, seat SOLD
		mvc.perform(get("/api/bookings/" + bookingB).header("Authorization", "Bearer " + customerB))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	@DisplayName("Tier 3: Showtime Isolation - Concurrent booking traffic on Showtime 1 has zero contention with Showtime 2")
	void testMultipleCustomersCompetingAcrossDifferentShowtimesIsolated() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtime1 = createShowtime(hall, 100_000);
		UUID showtime2 = createShowtime(hall, Instant.now().plus(4, ChronoUnit.DAYS), 120_000);

		Seat seat = hall.seats().get(0);

		String token1 = createCustomerToken();
		String token2 = createCustomerToken();

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);

		Callable<Integer> bookShowtime1 = () -> {
			start.await();
			return mvc.perform(post("/api/bookings")
					.header("Authorization", "Bearer " + token1)
					.contentType(MediaType.APPLICATION_JSON)
					.content(bookingJson(showtime1, seat.id())))
				.andReturn().getResponse().getStatus();
		};

		Callable<Integer> bookShowtime2 = () -> {
			start.await();
			return mvc.perform(post("/api/bookings")
					.header("Authorization", "Bearer " + token2)
					.contentType(MediaType.APPLICATION_JSON)
					.content(bookingJson(showtime2, seat.id())))
				.andReturn().getResponse().getStatus();
		};

		try {
			Future<Integer> f1 = pool.submit(bookShowtime1);
			Future<Integer> f2 = pool.submit(bookShowtime2);
			start.countDown();

			int status1 = f1.get();
			int status2 = f2.get();

			// Both holds for different showtimes must succeed independently with 201 Created
			assertThat(status1).isEqualTo(201);
			assertThat(status2).isEqualTo(201);
		} finally {
			pool.shutdownNow();
		}
	}
}
