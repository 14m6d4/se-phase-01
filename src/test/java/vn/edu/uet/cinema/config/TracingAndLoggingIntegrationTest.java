package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies real Spring Boot 4 Micrometer Tracing (Brave), filter ordering relative to
 * ServerHttpObservationFilter, W3C traceparent propagation, and native Logstash JSON log correlation
 * without needing Docker/Testcontainers.
 */
@SpringBootTest(classes = TracingAndLoggingIntegrationTest.TestApp.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class TracingAndLoggingIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Test
	void generatesTraceAndSpanHeadersAndCorrelatesStructuredJsonLogs(CapturedOutput output) throws Exception {
		MvcResult result = mvc.perform(get("/api/cinemas/ping"))
			.andExpect(status().isOk())
			.andReturn();

		String traceId = result.getResponse().getHeader(TraceResponseHeaderFilter.TRACE_ID_HEADER);
		String spanId = result.getResponse().getHeader(TraceResponseHeaderFilter.SPAN_ID_HEADER);

		assertThat(traceId).isNotBlank().matches("[0-9a-f]{16,32}");
		assertThat(spanId).isNotBlank().matches("[0-9a-f]{16}");

		List<String> matchingLines = output.getOut().lines()
			.filter(line -> line.startsWith("{") && line.contains(traceId) && line.contains(spanId))
			.toList();
		assertThat(matchingLines).as("JSON log lines containing traceId and spanId").isNotEmpty();
		String line = matchingLines.get(0);
		assertThat((String) JsonPath.read(line, "$.traceId")).isEqualTo(traceId);
		assertThat((String) JsonPath.read(line, "$.spanId")).isEqualTo(spanId);
	}

	@Test
	void propagatesIncomingW3cTraceparentIntoHeadersAndJsonLogs(CapturedOutput output) throws Exception {
		String incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
		String parentSpanId = "00f067aa0ba902b7";

		MvcResult result = mvc.perform(get("/api/cinemas/ping")
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
	void unauthorizedResponseStillCarriesTraceAndSpanHeaders() throws Exception {
		MvcResult result = mvc.perform(get("/api/bookings"))
			.andExpect(status().isUnauthorized())
			.andReturn();

		assertThat(result.getResponse().getHeader(TraceResponseHeaderFilter.TRACE_ID_HEADER))
			.isNotBlank().matches("[0-9a-f]{16,32}");
		assertThat(result.getResponse().getHeader(TraceResponseHeaderFilter.SPAN_ID_HEADER))
			.isNotBlank().matches("[0-9a-f]{16}");
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(excludeName = {
		"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
		"org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
		"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
	})
	@Import({ObservabilityConfig.class, SecurityConfig.class, PingController.class})
	static class TestApp {
	}

	@RestController
	static class PingController {
		@GetMapping("/api/cinemas/ping")
		String ping() {
			return "pong";
		}
	}
}
