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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.Seat;

/**
 * End-to-End Real-World Workload Test Suite (Tier 4).
 * Exercises realistic user journeys, flash-sale contention, high-throughput
 * sequential booking batches, and cancellation/refund workflows.
 */
class RealWorldWorkloadE2ETest extends E2ETestBase {

	@Test
	@DisplayName("Tier 4: Realistic Flash Sale Simulation - 20 virtual users storming a premier showtime with 20 seats")
	void testRealisticFlashSaleSimulation() throws Exception {
		int userCount = 20;
		Auditorium hall = createAuditorium(4, 5); // 20 seats total (4 rows, 5 columns)
		UUID showtimeId = createShowtime(hall, 120_000);
		List<Seat> allSeats = hall.seats();
		assertThat(allSeats.size()).isEqualTo(20);

		// Register 20 virtual customer tokens
		List<String> userTokens = new ArrayList<>();
		for (int i = 0; i < userCount; i++) {
			userTokens.add(createCustomerToken());
		}

		// Each user targets seat i (exact 1-to-1 matching initially)
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(userCount);

		List<Callable<MvcResult>> tasks = IntStream.range(0, userCount).mapToObj(i -> (Callable<MvcResult>) () -> {
			String token = userTokens.get(i);
			UUID targetedSeat = allSeats.get(i).id();
			start.await();
			return mvc.perform(post("/api/bookings")
					.header("Authorization", "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON)
					.content(bookingJson(showtimeId, targetedSeat)))
				.andReturn();
		}).toList();

		try {
			List<Future<MvcResult>> futures = tasks.stream().map(pool::submit).toList();
			start.countDown();

			int createdCount = 0;
			List<String> bookingIds = new ArrayList<>();

			for (Future<MvcResult> f : futures) {
				MvcResult res = f.get();
				if (res.getResponse().getStatus() == 201) {
					createdCount++;
					String bId = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
					bookingIds.add(bId);
				}
			}

			// All 20 disjoint seats must be held successfully
			assertThat(createdCount).isEqualTo(userCount);
			assertThat(bookingIds.size()).isEqualTo(userCount);

			// All 20 users now proceed to pay concurrently
			CountDownLatch payStart = new CountDownLatch(1);
			List<Callable<Integer>> payTasks = IntStream.range(0, userCount).mapToObj(i -> (Callable<Integer>) () -> {
				payStart.await();
				return pay(userTokens.get(i), bookingIds.get(i)).getResponse().getStatus();
			}).toList();

			List<Future<Integer>> payFutures = payTasks.stream().map(pool::submit).toList();
			payStart.countDown();

			int paidCount = 0;
			for (Future<Integer> pf : payFutures) {
				if (pf.get() == 200) paidCount++;
			}

			assertThat(paidCount).isEqualTo(userCount);

			// Inspect seat map via public API: all 20 seats must be SOLD
			String seatMapJson = mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

			List<String> soldStatuses = JsonPath.read(seatMapJson, "$.[?(@.status == 'SOLD')].status");
			assertThat(soldStatuses.size()).isEqualTo(20);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("Tier 4: Customer Journey - Discovery, Seat Map Inspection, Booking, and Payment Confirmation")
	void testCustomerDiscoveryAndBookingJourney() throws Exception {
		Auditorium hall = createAuditorium(3, 3);
		UUID showtimeId = createShowtime(hall, 150_000);
		String token = createCustomerToken();

		// Step 1: Browse showtime
		mvc.perform(get("/api/showtimes/" + showtimeId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SCHEDULED"));

		// Step 2: Browse seats
		mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(9));

		// Step 3: Hold 2 seats
		Seat s1 = hall.seats().get(0);
		Seat s2 = hall.seats().get(1);
		String bookingId = hold(token, showtimeId, s1.id(), s2.id());

		// Step 4: Pay
		pay(token, bookingId);

		// Step 5: Verify booking confirmation in customer booking list
		mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.[0].id").value(bookingId))
			.andExpect(jsonPath("$.[0].status").value("CONFIRMED"))
			.andExpect(jsonPath("$.[0].totalPrice").value(300_000));
	}

	@Test
	@DisplayName("Tier 4: Production Emergency - Cancellation of Showtime and Customer Re-booking on Another Showtime")
	void testCancellationAndRebookingWorkflow() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtime1 = createShowtime(hall, 100_000);
		UUID showtime2 = createShowtime(hall, Instant.now().plus(3, ChronoUnit.DAYS), 100_000);
		String adminToken = getAdminToken();
		String customer = createCustomerToken();

		// Customer books showtime 1
		String booking1 = hold(customer, showtime1, hall.seats().get(0).id());
		pay(customer, booking1);

		// Admin cancels showtime 1
		cancelShowtime(adminToken, showtime1, "POWER_OUTAGE", "Electrical failure in district");

		// Customer's booking on showtime 1 is REFUNDED
		mvc.perform(get("/api/bookings/" + booking1).header("Authorization", "Bearer " + customer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REFUNDED"));

		// Customer can now immediately book showtime 2 (no blocking pending booking)
		String booking2 = hold(customer, showtime2, hall.seats().get(0).id());
		pay(customer, booking2);

		// Verify booking 2 is CONFIRMED
		mvc.perform(get("/api/bookings/" + booking2).header("Authorization", "Bearer " + customer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}
}
