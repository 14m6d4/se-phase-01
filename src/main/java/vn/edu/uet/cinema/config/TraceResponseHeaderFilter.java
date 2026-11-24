package vn.edu.uet.cinema.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exposes the active Micrometer trace and span identifiers on every HTTP response header and emits a concise
 * structured access log line containing the trace correlation context from MDC (O1).
 */
public class TraceResponseHeaderFilter extends OncePerRequestFilter {

	public static final String TRACE_ID_HEADER = "X-Trace-Id";
	public static final String SPAN_ID_HEADER = "X-Span-Id";

	private static final Logger log = LoggerFactory.getLogger(TraceResponseHeaderFilter.class);

	private final Tracer tracer;

	public TraceResponseHeaderFilter(Tracer tracer) {
		this.tracer = tracer;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		long startNanos = System.nanoTime();
		TraceIds ids = resolveTraceIds();
		if (ids != null) {
			response.setHeader(TRACE_ID_HEADER, ids.traceId());
			response.setHeader(SPAN_ID_HEADER, ids.spanId());
		}
		try {
			filterChain.doFilter(request, response);
		} finally {
			if (ids != null && !response.isCommitted()) {
				if (response.getHeader(TRACE_ID_HEADER) == null) {
					response.setHeader(TRACE_ID_HEADER, ids.traceId());
				}
				if (response.getHeader(SPAN_ID_HEADER) == null) {
					response.setHeader(SPAN_ID_HEADER, ids.spanId());
				}
			}
			logRequest(request, response, System.nanoTime() - startNanos);
		}
	}

	private TraceIds resolveTraceIds() {
		if (tracer != null) {
			Span current = tracer.currentSpan();
			if (current != null) {
				TraceContext context = current.context();
				if (context != null && isPresent(context.traceId()) && isPresent(context.spanId())) {
					return new TraceIds(context.traceId(), context.spanId());
				}
			}
		}
		String traceId = MDC.get("traceId");
		String spanId = MDC.get("spanId");
		if (isPresent(traceId) && isPresent(spanId)) {
			return new TraceIds(traceId, spanId);
		}
		return null;
	}

	private void logRequest(HttpServletRequest request, HttpServletResponse response, long durationNanos) {
		String path = request.getRequestURI();
		double durationMs = durationNanos / 1_000_000.0;
		if (path.startsWith("/actuator/health")) {
			if (log.isDebugEnabled()) {
				log.debug("HTTP {} {} -> {} ({} ms)", request.getMethod(), path, response.getStatus(),
					String.format(Locale.ROOT, "%.2f", durationMs));
			}
			return;
		}
		if (log.isInfoEnabled()) {
			log.atInfo()
				.addKeyValue("http.method", request.getMethod())
				.addKeyValue("http.path", path)
				.addKeyValue("http.status", response.getStatus())
				.addKeyValue("duration_ms", Math.round(durationMs * 100.0) / 100.0)
				.log("HTTP request completed");
		}
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}

	private record TraceIds(String traceId, String spanId) {
	}
}
