package vn.edu.uet.cinema.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.config.BookingMetrics;
import vn.edu.uet.cinema.config.TraceResponseHeaderFilter;

/**
 * Verifies Issue #23 (O1): Micrometer tracing headers, structured JSON logging with traceId/spanId,
 * and per-step DB/commit/lock-wait/conflict metrics exposed via Actuator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityApiTest {

	@Autowired MockMvc mvc;
	@Autowired MovieService movies;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;
	@Autowired ShowtimeService showtimes;

	@Test
	void responsesIncludeTraceAndSpanHeadersAndStructuredJsonLogsCarryMatchingIds(CapturedOutput output) throws Exception {
		MvcResult result = mvc.perform(get("/api/movies"))
			.andExpect(status().isOk())
			.andReturn();

		String traceId = result.getResponse().getHeader(TraceResponseHeaderFilter.TRACE_ID_HEADER);
		String spanId = result.getResponse().getHeader(TraceResponseHeaderFilter.SPAN_ID_HEADER);

		assertThat(traceId).isNotBlank().matches("[0-9a-f]{16,32}");
		assertThat(spanId).isNotBlank().matches("[0-9a-f]{16}");

		// Structured JSON log (logstash format) must contain a valid JSON line with the exact traceId and spanId.
		List<String> matchingLines = output.getOut().lines()
			.filter(line -> line.startsWith("{") && line.contains(traceId) && line.contains(spanId))
			.toList();
		assertThat(matchingLines).isNotEmpty();
		String jsonLine = matchingLines.get(0);
		assertThat((String) JsonPath.read(jsonLine, "$.traceId")).isEqualTo(traceId);
		assertThat((String) JsonPath.read(jsonLine, "$.spanId")).isEqualTo(spanId);
	}

	@Test
	void incomingW3cTraceparentHeaderIsPropagatedToResponseHeaderAndLogs(CapturedOutput output) throws Exception {
		String incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
		String parentSpanId = "00f067aa0ba902b7";

		MvcResult result = mvc.perform(get("/api/cinemas")
				.header("traceparent", "00-" + incomingTraceId + "-" + parentSpanId + "-01"))
			.andExpect(status().isOk())
			.andReturn();

		String traceId = result.getResponse().getHeader(TraceResponseHeaderFilter.TRACE_ID_HEADER);
		String spanId = result.getResponse().getHeader(TraceResponseHeaderFilter.SPAN_ID_HEADER);

		assertThat(traceId).isEqualTo(incomingTraceId);
		assertThat(spanId).isNotBlank().isNotEqualTo(parentSpanId);
		assertThat(output.getOut()).contains("\"traceId\":\"" + incomingTraceId + "\"")
			.contains("\"spanId\":\"" + spanId + "\"");
	}

	@Test
	void bookingFlowPublishesDbQueryCommitLockWaitAndConflictMetricsToActuator() throws Exception {
		UUID cinemaId = cinemas.create("Obs Cinema " + UUID.randomUUID(), "Address").id();
		Auditorium hall = auditoriums.create(cinemaId, "Hall", 2, 2, List.of(), List.of());
		UUID movieId = movies.create("Obs Film", "Synopsis", 90, "Drama", AgeRating.P,
			LocalDate.parse("2026-09-01"), "poster").id();
		UUID showtimeId = showtimes.create(movieId, hall.id(), Instant.now().plus(1, ChronoUnit.DAYS), 100_000).id();
		UUID seat1 = hall.seats().get(0).id();
		UUID seat2 = hall.seats().get(1).id();

		String customer1 = registerAndLoginCustomer();
		String customer2 = registerAndLoginCustomer();
		String admin = loginAdmin();

		// 1. Hold seat1 -> succeeds (201)
		String booking1 = JsonPath.read(mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customer1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}".formatted(showtimeId, seat1)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id");

		// 2. Customer 2 tries to hold seat1 -> 409 seats_taken
		MvcResult conflictResult = mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customer2)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}".formatted(showtimeId, seat1)))
			.andExpect(status().isConflict())
			.andReturn();
		assertThat(conflictResult.getResponse().getHeader(TraceResponseHeaderFilter.TRACE_ID_HEADER)).isNotBlank();

		// 3. Pay booking1 -> succeeds (200)
		mvc.perform(post("/api/bookings/" + booking1 + "/payment")
				.header("Authorization", "Bearer " + customer1))
			.andExpect(status().isOk());

		// 4. Customer 2 holds seat2 and abandons it -> 201 then 204
		String booking2 = JsonPath.read(mvc.perform(post("/api/bookings")
				.header("Authorization", "Bearer " + customer2)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showtimeId\":\"%s\",\"seatIds\":[\"%s\"]}".formatted(showtimeId, seat2)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(delete("/api/bookings/" + booking2)
				.header("Authorization", "Bearer " + customer2))
			.andExpect(status().isNoContent());

		// 5. Verify Actuator metrics require ADMIN
		mvc.perform(get("/actuator/metrics/" + BookingMetrics.DB_COMMIT_TIMER))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/metrics/" + BookingMetrics.DB_COMMIT_TIMER)
				.header("Authorization", "Bearer " + customer1))
			.andExpect(status().isForbidden());

		// 6. Verify per-step DB query, commit, lock wait, operation, and 409 reason metrics exist for ADMIN
		mvc.perform(get("/actuator/metrics/" + BookingMetrics.OPERATION_TIMER)
				.header("Authorization", "Bearer " + admin)
				.param("tag", "operation:hold"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());

		mvc.perform(get("/actuator/metrics/" + BookingMetrics.DB_QUERY_TIMER)
				.header("Authorization", "Bearer " + admin)
				.param("tag", "step:persist_booking"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());

		mvc.perform(get("/actuator/metrics/" + BookingMetrics.DB_COMMIT_TIMER)
				.header("Authorization", "Bearer " + admin)
				.param("tag", "operation:hold"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());

		mvc.perform(get("/actuator/metrics/" + BookingMetrics.DB_COMMIT_TIMER)
				.header("Authorization", "Bearer " + admin)
				.param("tag", "operation:pay"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());

		mvc.perform(get("/actuator/metrics/" + BookingMetrics.LOCK_WAIT_TIMER)
				.header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());

		mvc.perform(get("/actuator/metrics/" + BookingMetrics.HTTP_CONFLICT_COUNTER)
				.header("Authorization", "Bearer " + admin)
				.param("tag", "reason:seats_taken"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());
	}

	private String registerAndLoginCustomer() throws Exception {
		String email = "obs-" + UUID.randomUUID() + "@example.com";
		mvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Observer\"}".formatted(email)))
			.andExpect(status().isCreated());
		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private String loginAdmin() throws Exception {
		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"admin@cinema.local\",\"password\":\"admin12345\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
