package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceResponseHeaderFilterTest {

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void writesTraceAndSpanHeadersFromActiveTracerSpan() throws Exception {
		Tracer tracer = tracerWith("0123456789abcdef0123456789abcdef", "0123456789abcdef");

		TraceResponseHeaderFilter filter = new TraceResponseHeaderFilter(tracer);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bookings");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(409);

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(response.getHeader(TraceResponseHeaderFilter.TRACE_ID_HEADER))
			.isEqualTo("0123456789abcdef0123456789abcdef");
		assertThat(response.getHeader(TraceResponseHeaderFilter.SPAN_ID_HEADER))
			.isEqualTo("0123456789abcdef");
	}

	@Test
	void fallsBackToMdcWhenTracerHasNoCurrentSpan() throws Exception {
		MDC.put("traceId", "abcdef0123456789abcdef0123456789");
		MDC.put("spanId", "abcdef0123456789");

		TraceResponseHeaderFilter filter = new TraceResponseHeaderFilter(null);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/movies");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> { });

		assertThat(response.getHeader(TraceResponseHeaderFilter.TRACE_ID_HEADER))
			.isEqualTo("abcdef0123456789abcdef0123456789");
		assertThat(response.getHeader(TraceResponseHeaderFilter.SPAN_ID_HEADER))
			.isEqualTo("abcdef0123456789");
	}

	@Test
	void omitsTraceHeadersWhenNoTraceContextIsPresent() throws Exception {
		TraceResponseHeaderFilter filter = new TraceResponseHeaderFilter(null);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> { });

		assertThat(response.getHeader(TraceResponseHeaderFilter.TRACE_ID_HEADER)).isNull();
		assertThat(response.getHeader(TraceResponseHeaderFilter.SPAN_ID_HEADER)).isNull();
	}

	private static Tracer tracerWith(String traceId, String spanId) {
		TraceContext context = (TraceContext) Proxy.newProxyInstance(
			TraceContext.class.getClassLoader(),
			new Class<?>[] { TraceContext.class },
			(proxy, method, args) -> switch (method.getName()) {
				case "traceId" -> traceId;
				case "spanId" -> spanId;
				default -> null;
			});
		Span span = (Span) Proxy.newProxyInstance(
			Span.class.getClassLoader(),
			new Class<?>[] { Span.class },
			(proxy, method, args) -> "context".equals(method.getName()) ? context : null);
		return (Tracer) Proxy.newProxyInstance(
			Tracer.class.getClassLoader(),
			new Class<?>[] { Tracer.class },
			(proxy, method, args) -> "currentSpan".equals(method.getName()) ? span : null);
	}
}
