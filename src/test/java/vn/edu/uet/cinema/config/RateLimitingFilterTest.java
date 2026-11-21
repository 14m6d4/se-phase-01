package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitingFilterTest {

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void requestsWithinLimitPassThrough() throws ServletException, IOException {
		RateLimitingFilter filter = new RateLimitingFilter(true, 5, 5, 60, 5, 5, 60);
		FilterChain chain = mock(FilterChain.class);

		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
		request.setRemoteAddr("192.168.1.10");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(chain, times(1)).doFilter(request, response);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void authEndpointExceedingLimitReturns429WithProblemJsonAndRetryAfter() throws ServletException, IOException {
		RateLimitingFilter filter = new RateLimitingFilter(true, 2, 2, 60, 10, 10, 60);
		FilterChain chain = mock(FilterChain.class);

		MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/auth/login");
		req1.setRemoteAddr("10.0.0.1");
		MockHttpServletResponse res1 = new MockHttpServletResponse();
		filter.doFilter(req1, res1, chain);
		assertThat(res1.getStatus()).isEqualTo(200);

		MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/api/auth/login");
		req2.setRemoteAddr("10.0.0.1");
		MockHttpServletResponse res2 = new MockHttpServletResponse();
		filter.doFilter(req2, res2, chain);
		assertThat(res2.getStatus()).isEqualTo(200);

		// Third request exceeds capacity (2)
		MockHttpServletRequest req3 = new MockHttpServletRequest("POST", "/api/auth/login");
		req3.setRemoteAddr("10.0.0.1");
		MockHttpServletResponse res3 = new MockHttpServletResponse();
		filter.doFilter(req3, res3, chain);

		assertThat(res3.getStatus()).isEqualTo(429);
		assertThat(res3.getContentType()).isEqualTo("application/problem+json");
		assertThat(res3.getHeader("Retry-After")).isNotNull();
		assertThat(res3.getContentAsString()).contains("\"status\":429")
			.contains("\"title\":\"Too Many Requests\"");

		// Verify chain was only invoked for the first 2 allowed requests
		verify(chain, times(1)).doFilter(req1, res1);
		verify(chain, times(1)).doFilter(req2, res2);
		verify(chain, never()).doFilter(req3, res3);
	}

	@Test
	void differentIpsHaveIsolatedBucketsOnAuthEndpoint() throws ServletException, IOException {
		RateLimitingFilter filter = new RateLimitingFilter(true, 1, 1, 60, 10, 10, 60);
		FilterChain chain = mock(FilterChain.class);

		MockHttpServletRequest ip1Req = new MockHttpServletRequest("POST", "/api/auth/login");
		ip1Req.setRemoteAddr("10.0.0.1");
		MockHttpServletResponse ip1Res = new MockHttpServletResponse();
		filter.doFilter(ip1Req, ip1Res, chain);
		assertThat(ip1Res.getStatus()).isEqualTo(200);

		// Second request from different IP should be allowed
		MockHttpServletRequest ip2Req = new MockHttpServletRequest("POST", "/api/auth/login");
		ip2Req.setRemoteAddr("10.0.0.2");
		MockHttpServletResponse ip2Res = new MockHttpServletResponse();
		filter.doFilter(ip2Req, ip2Res, chain);
		assertThat(ip2Res.getStatus()).isEqualTo(200);
	}

	@Test
	void authenticatedApiEndpointIsKeyedByUser() throws ServletException, IOException {
		RateLimitingFilter filter = new RateLimitingFilter(true, 10, 10, 60, 1, 1, 60);
		FilterChain chain = mock(FilterChain.class);

		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken("user-123", "n/a", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));

		MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/bookings");
		req1.setRemoteAddr("10.0.0.1");
		MockHttpServletResponse res1 = new MockHttpServletResponse();
		filter.doFilter(req1, res1, chain);
		assertThat(res1.getStatus()).isEqualTo(200);

		// Second request from same user (even if from another IP) is throttled
		MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/bookings");
		req2.setRemoteAddr("10.0.0.99");
		MockHttpServletResponse res2 = new MockHttpServletResponse();
		filter.doFilter(req2, res2, chain);
		assertThat(res2.getStatus()).isEqualTo(429);
	}

	@Test
	void nonApiEndpointsAreNotFiltered() throws ServletException, IOException {
		RateLimitingFilter filter = new RateLimitingFilter(true, 1, 1, 60, 1, 1, 60);
		FilterChain chain = mock(FilterChain.class);

		MockHttpServletRequest healthReq = new MockHttpServletRequest("GET", "/actuator/health");
		MockHttpServletResponse healthRes = new MockHttpServletResponse();
		filter.doFilter(healthReq, healthRes, chain);
		verify(chain, times(1)).doFilter(healthReq, healthRes);

		MockHttpServletRequest docsReq = new MockHttpServletRequest("GET", "/v3/api-docs");
		MockHttpServletResponse docsRes = new MockHttpServletResponse();
		filter.doFilter(docsReq, docsRes, chain);
		verify(chain, times(1)).doFilter(docsReq, docsRes);
	}

	@Test
	void filterDisabledAllowsAllRequests() throws ServletException, IOException {
		RateLimitingFilter filter = new RateLimitingFilter(false, 1, 1, 60, 1, 1, 60);
		FilterChain chain = mock(FilterChain.class);

		for (int i = 0; i < 5; i++) {
			MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
			req.setRemoteAddr("10.0.0.1");
			MockHttpServletResponse res = new MockHttpServletResponse();
			filter.doFilter(req, res, chain);
			assertThat(res.getStatus()).isEqualTo(200);
		}
		verify(chain, times(5)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}
}
