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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.Seat;

/**
 * End-to-End Concurrency & Multithread Contention Test Suite (Tier 3 & Tier 4).
 * Exercises realistic multithreaded booking workloads, flash-sale contention,
 * and lock inversion avoidance over HTTP against real PostgreSQL.
 */
class BookingConcurrencyE2ETest extends E2ETestBase {

	@Test
	@DisplayName("Tier 4: 10 concurrent customer threads competing for exact same seats produce exactly 1 winner and 9 409 conflicts")
	void testConcurrentBookingsSameSeatsSingleWinner() throws Exception {
		int threadCount = 10;
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		Seat seatA = hall.seats().get(0);
		Seat seatB = hall.seats().get(1);

		List<String> customerTokens = new ArrayList<>();
		for (int i = 0; i < threadCount; i++) {
			customerTokens.add(createCustomerToken());
		}

		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);

		List<Callable<Integer>> tasks = customerTokens.stream().map(token -> (Callable<Integer>) () -> {
			readyLatch.countDown();
			startLatch.await();
			MvcResult result = mvc.perform(post("/api/bookings")
					.header("Authorization", "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON)
					.content(bookingJson(showtimeId, seatA.id(), seatB.id())))
				.andReturn();
			return result.getResponse().getStatus();
		}).toList();

		try {
			List<Future<Integer>> futures = tasks.stream().map(executor::submit).toList();
			readyLatch.await();
			startLatch.countDown();

			int successCount = 0;
			int conflictCount = 0;
			int otherErrors = 0;

			for (Future<Integer> future : futures) {
				int statusCode = future.get();
				if (statusCode == 201) {
					successCount++;
				} else if (statusCode == 409) {
					conflictCount++;
				} else {
					otherErrors++;
				}
			}

			assertThat(successCount)
				.as("Exactly one customer thread must win the contested seats")
				.isEqualTo(1);
			assertThat(conflictCount)
				.as("All losing threads must receive 409 Conflict")
				.isEqualTo(threadCount - 1);
			assertThat(otherErrors)
				.as("No thread should receive 500 or any unexpected status code")
				.isEqualTo(0);

			// Verify seat map state via HTTP API
			mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.[?(@.id == '%s')].status", seatA.id()).value(contains("HELD")))
				.andExpect(jsonPath("$.[?(@.id == '%s')].status", seatB.id()).value(contains("HELD")));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("Tier 3 / Tier 4: Concurrent holds with opposite seat order ([A, B] vs [B, A]) avoid deadlock crash and produce 1 winner")
	void testOppositeSeatOrderConcurrencyNoDeadlockCrash() throws Exception {
		int threadCount = 10;
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 120_000);
		Seat seatA = hall.seats().get(0);
		Seat seatB = hall.seats().get(1);

		List<String> customerTokens = new ArrayList<>();
		for (int i = 0; i < threadCount; i++) {
			customerTokens.add(createCustomerToken());
		}

		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);

		List<Callable<MvcResult>> tasks = IntStream.range(0, threadCount).mapToObj(i -> (Callable<MvcResult>) () -> {
			String token = customerTokens.get(i);
			UUID first = (i % 2 == 0) ? seatA.id() : seatB.id();
			UUID second = (i % 2 == 0) ? seatB.id() : seatA.id();
			readyLatch.countDown();
			startLatch.await();
			return mvc.perform(post("/api/bookings")
					.header("Authorization", "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON)
					.content(bookingJson(showtimeId, first, second)))
				.andReturn();
		}).toList();

		try {
			List<Future<MvcResult>> futures = tasks.stream().map(executor::submit).toList();
			readyLatch.await();
			startLatch.countDown();

			int created = 0;
			int conflict = 0;
			for (Future<MvcResult> f : futures) {
				int status = f.get().getResponse().getStatus();
				if (status == 201) {
					created++;
				} else if (status == 409) {
					conflict++;
					String content = f.get().getResponse().getContentAsString();
					// Must contain problem details with seatIds
					assertThat(content).contains("seatIds");
				}
			}

			assertThat(created).as("Exactly one winner for opposite seat ordering").isEqualTo(1);
			assertThat(conflict).as("All other threads receive 409 Conflict").isEqualTo(threadCount - 1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("Tier 3: Concurrent hold vs cart abandonment resolves cleanly without 500 error")
	void testConcurrentHoldAndAbandonRace() throws Exception {
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		Seat seat = hall.seats().get(0);

		String customerA = createCustomerToken();
		String customerB = createCustomerToken();

		// Customer A holds the seat
		String bookingId = hold(customerA, showtimeId, seat.id());

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);

		Callable<Integer> abandonTask = () -> {
			start.await();
			return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
					.delete("/api/bookings/" + bookingId)
					.header("Authorization", "Bearer " + customerA))
				.andReturn().getResponse().getStatus();
		};

		Callable<Integer> holdTask = () -> {
			start.await();
			return mvc.perform(post("/api/bookings")
					.header("Authorization", "Bearer " + customerB)
					.contentType(MediaType.APPLICATION_JSON)
					.content(bookingJson(showtimeId, seat.id())))
				.andReturn().getResponse().getStatus();
		};

		try {
			Future<Integer> fAbandon = pool.submit(abandonTask);
			Future<Integer> fHold = pool.submit(holdTask);
			start.countDown();

			int abandonStatus = fAbandon.get();
			int holdStatus = fHold.get();

			assertThat(abandonStatus).as("Abandon should return 204 No Content").isEqualTo(204);
			// Customer B either succeeds (201) if abandon completed first, or receives 409 if abandon completed second
			assertThat(holdStatus).as("Hold must be either 201 or 409, never 500").isIn(201, 409);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("Tier 2 / Tier 4: Lapsed hold takeover under concurrent demand purges old booking and grants 1 new hold")
	void testLapsedHoldConcurrentTakeover() throws Exception {
		int competitors = 5;
		Auditorium hall = createAuditorium(2, 2);
		UUID showtimeId = createShowtime(hall, 100_000);
		Seat seat = hall.seats().get(0);

		// Seed a lapsed booking directly via domain service (hold expired 15 minutes ago)
		String customerLapsed = createCustomerToken();
		String meResponse = mvc.perform(get("/api/me").header("Authorization", "Bearer " + customerLapsed))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		UUID lapsedCustomerId = UUID.fromString(JsonPath.read(meResponse, "$.id"));
		bookings.book(lapsedCustomerId, showtimeId, List.of(seat.id()), Instant.now().minus(15, ChronoUnit.MINUTES));

		// Now 5 concurrent competitors race to hold this lapsed seat
		List<String> competitorsTokens = new ArrayList<>();
		for (int i = 0; i < competitors; i++) {
			competitorsTokens.add(createCustomerToken());
		}

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(competitors);

		List<Callable<Integer>> tasks = competitorsTokens.stream().map(token -> (Callable<Integer>) () -> {
			start.await();
			return mvc.perform(post("/api/bookings")
					.header("Authorization", "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON)
					.content(bookingJson(showtimeId, seat.id())))
				.andReturn().getResponse().getStatus();
		}).toList();

		try {
			List<Future<Integer>> futures = tasks.stream().map(executor::submit).toList();
			start.countDown();

			int success = 0;
			int conflict = 0;
			for (Future<Integer> f : futures) {
				int st = f.get();
				if (st == 201) success++;
				if (st == 409) conflict++;
			}

			assertThat(success).as("Exactly 1 competitor wins the lapsed seat").isEqualTo(1);
			assertThat(conflict).as("4 competitors receive 409 Conflict").isEqualTo(competitors - 1);

			// Verify old booking is purged/overwritten and seat map is HELD
			mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.[?(@.id == '%s')].status", seat.id()).value(contains("HELD")));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("Tier 4: Realistic Flash Sale with 16 users competing for 4 seats ensures zero duplicate bookings")
	void testFlashSaleSimultaneousDemand() throws Exception {
		int userCount = 16;
		Auditorium hall = createAuditorium(2, 2); // 4 seats total: A1, A2, B1, B2
		UUID showtimeId = createShowtime(hall, 150_000);
		List<Seat> seats = hall.seats();

		List<String> tokens = new ArrayList<>();
		for (int i = 0; i < userCount; i++) {
			tokens.add(createCustomerToken());
		}

		// Users request overlapping pairs:
		// Users 0..3 request [Seat 0, Seat 1]
		// Users 4..7 request [Seat 1, Seat 2]
		// Users 8..11 request [Seat 2, Seat 3]
		// Users 12..15 request [Seat 3, Seat 0]
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(userCount);

		List<Callable<MvcResult>> callables = IntStream.range(0, userCount).mapToObj(i -> (Callable<MvcResult>) () -> {
			String token = tokens.get(i);
			int firstIdx = i / 4;
			int secondIdx = (firstIdx + 1) % seats.size();
			UUID seat1 = seats.get(firstIdx).id();
			UUID seat2 = seats.get(secondIdx).id();
			start.await();
			return mvc.perform(post("/api/bookings")
					.header("Authorization", "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON)
					.content(bookingJson(showtimeId, seat1, seat2)))
				.andReturn();
		}).toList();

		try {
			List<Future<MvcResult>> results = callables.stream().map(executor::submit).toList();
			start.countDown();

			AtomicInteger winners = new AtomicInteger();
			AtomicInteger losers = new AtomicInteger();

			for (Future<MvcResult> r : results) {
				int status = r.get().getResponse().getStatus();
				if (status == 201) winners.incrementAndGet();
				else if (status == 409) losers.incrementAndGet();
			}

			// In any pairing of 4 seats where each requests 2 adjacent seats in a 4-cycle,
			// at most 2 non-adjacent bookings can win (e.g. [0,1] and [2,3]).
			assertThat(winners.get()).as("At least 1 and at most 2 winners can win non-overlapping pairs").isBetween(1, 2);
			assertThat(losers.get()).isEqualTo(userCount - winners.get());

			// Now verify seat status in seat map: exactly winners * 2 seats are HELD
			String seatMapJson = mvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			List<String> heldStatuses = JsonPath.read(seatMapJson, "$.[?(@.status == 'HELD')].status");
			assertThat(heldStatuses.size()).isEqualTo(winners.get() * 2);
		} finally {
			executor.shutdownNow();
		}
	}
}
