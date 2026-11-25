package vn.edu.uet.cinema.api.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.booking.PaymentTransaction;
import vn.edu.uet.cinema.business.booking.PaymentTransactionRepository;
import vn.edu.uet.cinema.business.booking.PaymentTransactionStatus;
import vn.edu.uet.cinema.business.booking.PaymentTransactionType;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;

/**
 * HTTP API integration tests for payment & refund idempotency (Issue #17 / S3).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentIdempotencyApiTest {

	@Autowired MockMvc mvc;
	@Autowired MovieService movies;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;
	@Autowired ShowtimeService showtimes;
	@Autowired BookingService bookings;
	@Autowired PaymentTransactionRepository paymentTransactions;

	@Test
	void retryingPaymentWithSameIdempotencyKeyReturnsIdenticalResponseAndSingleTransaction() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		String token = customer();
		String bookingId = hold(token, showtimeId, hall.seats().get(0).id());
		String key = "http-pay-" + UUID.randomUUID();

		String first = mvc.perform(post("/api/bookings/" + bookingId + "/payment")
				.header("Authorization", "Bearer " + token)
				.header(BookingController.IDEMPOTENCY_KEY_HEADER, key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(bookingId))
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andReturn().getResponse().getContentAsString();

		String second = mvc.perform(post("/api/bookings/" + bookingId + "/payment")
				.header("Authorization", "Bearer " + token)
				.header(BookingController.IDEMPOTENCY_KEY_HEADER, key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(bookingId))
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		List<PaymentTransaction> txs = paymentTransactions.findByBookingId(UUID.fromString(bookingId));
		assertThat(txs).hasSize(1);
		assertThat(txs.get(0).type()).isEqualTo(PaymentTransactionType.PAYMENT);
		assertThat(txs.get(0).status()).isEqualTo(PaymentTransactionStatus.COMPLETED);
		assertThat(txs.get(0).idempotencyKey()).isEqualTo(key);
	}

	@Test
	void reusingIdempotencyKeyForDifferentBookingReturns409AndLeavesSecondBookingPending() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		String token = customer();
		String key = "reuse-http-" + UUID.randomUUID();

		String firstId = hold(token, showtimeId, hall.seats().get(0).id());
		mvc.perform(post("/api/bookings/" + firstId + "/payment")
				.header("Authorization", "Bearer " + token)
				.header(BookingController.IDEMPOTENCY_KEY_HEADER, key))
			.andExpect(status().isOk());

		String secondId = hold(token, showtimeId, hall.seats().get(1).id());
		mvc.perform(post("/api/bookings/" + secondId + "/payment")
				.header("Authorization", "Bearer " + token)
				.header(BookingController.IDEMPOTENCY_KEY_HEADER, key))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value(containsString("Idempotency-Key")));

		mvc.perform(get("/api/bookings/" + secondId).header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"));
		assertThat(paymentTransactions.findByBookingId(UUID.fromString(secondId))).isEmpty();
	}

	@Test
	void invalidIdempotencyKeyHeaderReturns400ProblemDetail() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		String token = customer();
		String bookingId = hold(token, showtimeId, hall.seats().get(0).id());

		for (String badKey : List.of("", "   ", "a".repeat(129))) {
			mvc.perform(post("/api/bookings/" + bookingId + "/payment")
					.header("Authorization", "Bearer " + token)
					.header(BookingController.IDEMPOTENCY_KEY_HEADER, badKey))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
		}

		mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	void latePaymentOnExpiredHoldAndShowtimeCancellationRefundAreBothIdempotent() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		String token = customer();
		UUID customerId = UUID.fromString(JsonPath.read(mvc.perform(get("/api/me")
			.header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString(), "$.id"));

		// 1. Expired hold paid late twice with same Idempotency-Key -> 409 both times, 1 PAYMENT/REFUNDED record
		UUID expiredId = bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()),
			Instant.now().minus(11, ChronoUnit.MINUTES)).id();
		String lateKey = "late-http-" + UUID.randomUUID();

		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/api/bookings/" + expiredId + "/payment")
					.header("Authorization", "Bearer " + token)
					.header(BookingController.IDEMPOTENCY_KEY_HEADER, lateKey))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(containsString("EXPIRED")));
		}
		assertThat(paymentTransactions.findByBookingId(expiredId))
			.hasSize(1)
			.first()
			.satisfies(tx -> {
				assertThat(tx.type()).isEqualTo(PaymentTransactionType.PAYMENT);
				assertThat(tx.status()).isEqualTo(PaymentTransactionStatus.REFUNDED);
			});

		// 2. Paid booking refunded via showtime cancellation -> retry cancel returns 409, still 1 REFUND record
		String paidId = hold(token, showtimeId, hall.seats().get(1).id());
		mvc.perform(post("/api/bookings/" + paidId + "/payment")
				.header("Authorization", "Bearer " + token)
				.header(BookingController.IDEMPOTENCY_KEY_HEADER, "pay-ok-" + UUID.randomUUID()))
			.andExpect(status().isOk());

		String adminToken = adminToken();
		mvc.perform(post("/api/showtimes/" + showtimeId + "/cancellation")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"POWER_OUTAGE\"}"))
			.andExpect(status().isOk());

		mvc.perform(post("/api/showtimes/" + showtimeId + "/cancellation")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"POWER_OUTAGE\"}"))
			.andExpect(status().isConflict());

		List<PaymentTransaction> paidTxs = paymentTransactions.findByBookingId(UUID.fromString(paidId));
		assertThat(paidTxs).hasSize(2);
		assertThat(paidTxs).filteredOn(tx -> tx.type() == PaymentTransactionType.PAYMENT).hasSize(1);
		assertThat(paidTxs).filteredOn(tx -> tx.type() == PaymentTransactionType.REFUND)
			.hasSize(1)
			.first().extracting(PaymentTransaction::status).isEqualTo(PaymentTransactionStatus.REFUNDED);
	}

	private Auditorium auditorium() {
		UUID cinemaId = cinemas.create("Cinema " + UUID.randomUUID(), "Address").id();
		return auditoriums.create(cinemaId, "Hall", 2, 2, List.of(), List.of());
	}

	private UUID showtime(Auditorium hall) {
		UUID movieId = movies.create("Film", "Synopsis", 90, "Drama", AgeRating.P, LocalDate.parse("2026-09-01"),
			"poster").id();
		return showtimes.create(movieId, hall.id(), Instant.now().plus(1, ChronoUnit.DAYS), 100_000).id();
	}

	private String hold(String token, UUID showtimeId, UUID seatId) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}".formatted(showtimeId, seatId)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private String customer() throws Exception {
		String email = "idemp-api-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Customer\"}".formatted(email)))
			.andExpect(status().isCreated());
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private String adminToken() throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"admin@cinema.local\",\"password\":\"admin12345\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
