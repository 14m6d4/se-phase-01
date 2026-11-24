package vn.edu.uet.cinema.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires observability components (Micrometer booking metrics and HTTP trace response headers) outside the
 * framework-free business layer (ADR 0001, O1).
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

	@Bean
	BookingMetrics bookingMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
		return new BookingMetrics(meterRegistry.getIfAvailable());
	}

	@Bean
	FilterRegistrationBean<TraceResponseHeaderFilter> traceResponseHeaderFilter(ObjectProvider<Tracer> tracer) {
		FilterRegistrationBean<TraceResponseHeaderFilter> registration =
			new FilterRegistrationBean<>(new TraceResponseHeaderFilter(tracer.getIfAvailable()));
		// Run immediately after ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1) and before Spring Security,
		// so 401/403/429 responses also include X-Trace-Id and X-Span-Id headers.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
		return registration;
	}
}
