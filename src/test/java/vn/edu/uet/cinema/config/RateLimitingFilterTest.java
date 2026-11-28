package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
		verify(chain, times(5)).doFilter(any(), any());
	}

	@Test
	void spoofedXForwardedForDoesNotBypassAuthOrApiRateLimit() throws ServletException, IOException {
		RateLimitingFilter filter = new RateLimitingFilter(true, 2, 2, 60, 2, 2, 60);
		FilterChain chain = mock(FilterChain.class);

		for (int i = 0; i < 2; i++) {
			MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/register");
			req.setRemoteAddr("198.51.100.5");
			req.addHeader("X-Forwarded-For", "1.1.1." + i);
			MockHttpServletResponse res = new MockHttpServletResponse();
			filter.doFilter(req, res, chain);
			assertThat(res.getStatus()).isEqualTo(200);
		}

		MockHttpServletRequest authBlocked = new MockHttpServletRequest("POST", "/api/auth/register");
		authBlocked.setRemoteAddr("198.51.100.5");
		authBlocked.addHeader("X-Forwarded-For", "9.9.9.9");
		MockHttpServletResponse authBlockedRes = new MockHttpServletResponse();
		filter.doFilter(authBlocked, authBlockedRes, chain);
		assertThat(authBlockedRes.getStatus()).isEqualTo(429);

		for (int i = 0; i < 2; i++) {
			MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/movies");
			req.setRemoteAddr("198.51.100.6");
			req.addHeader("X-Forwarded-For", "2.2.2." + i);
			MockHttpServletResponse res = new MockHttpServletResponse();
			filter.doFilter(req, res, chain);
			assertThat(res.getStatus()).isEqualTo(200);
		}

		MockHttpServletRequest apiBlocked = new MockHttpServletRequest("GET", "/api/movies");
		apiBlocked.setRemoteAddr("198.51.100.6");
		apiBlocked.addHeader("X-Forwarded-For", "8.8.8.8");
		MockHttpServletResponse apiBlockedRes = new MockHttpServletResponse();
		filter.doFilter(apiBlocked, apiBlockedRes, chain);
		assertThat(apiBlockedRes.getStatus()).isEqualTo(429);
	}

	@Test
	void bucketStoreIsStrictlyBoundedAndExpiresIdleBucketsByTtl() throws ServletException, IOException {
		AtomicLong nanos = new AtomicLong(1_000_000_000L);
		RateLimitingFilter filter = new RateLimitingFilter(
			true, 1, 1, 60, 5, 5, 60, 10, 10, 60, 4, 10, nanos::get);
		FilterChain chain = mock(FilterChain.class);

		for (int i = 0; i < 25; i++) {
			MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/register");
			req.setRemoteAddr("203.0.113." + i);
			MockHttpServletResponse res = new MockHttpServletResponse();
			filter.doFilter(req, res, chain);
			assertThat(res.getStatus()).isEqualTo(200);
			assertThat(filter.bucketCount()).isLessThanOrEqualTo(4);
		}

		// Exhaust 203.0.113.24, then advance beyond bucket TTL (10s) -> bucket expires and fresh request is allowed.
		MockHttpServletRequest exhausted = new MockHttpServletRequest("POST", "/api/auth/register");
		exhausted.setRemoteAddr("203.0.113.24");
		MockHttpServletResponse exhaustedRes = new MockHttpServletResponse();
		filter.doFilter(exhausted, exhaustedRes, chain);
		assertThat(exhaustedRes.getStatus()).isEqualTo(429);

		nanos.addAndGet(11_000_000_000L);
		MockHttpServletRequest afterTtl = new MockHttpServletRequest("POST", "/api/auth/register");
		afterTtl.setRemoteAddr("203.0.113.24");
		MockHttpServletResponse afterTtlRes = new MockHttpServletResponse();
		filter.doFilter(afterTtl, afterTtlRes, chain);
		assertThat(afterTtlRes.getStatus()).isEqualTo(200);
	}

	@Test
	void loginEnforcesPerAccountLimitAcrossDistinctIpsAndPreservesReplayableBody() throws ServletException, IOException {
		AtomicLong nanos = new AtomicLong(1_000_000_000L);
		RateLimitingFilter filter = new RateLimitingFilter(
			true, 50, 50, 60, 2, 2, 60, 100, 100, 60, 100, 600, nanos::get);
		FilterChain chain = mock(FilterChain.class);

		String body1 = "{\"email\":\"Victim@Example.com\",\"password\":\"bad1\"}";
		String body2 = "{\"email\":\"  victim@example.com  \",\"password\":\"bad2\"}";
		doAnswer(inv -> {
			HttpServletRequest passed = inv.getArgument(0);
			String read = new String(passed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(read).isIn(body1, body2);
			return null;
		}).when(chain).doFilter(any(), any());

		MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/auth/login");
		req1.setRemoteAddr("10.1.0.1");
		req1.setContentType("application/json");
		req1.setContent(body1.getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse res1 = new MockHttpServletResponse();
		filter.doFilter(req1, res1, chain);
		assertThat(res1.getStatus()).isEqualTo(200);

		MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/api/auth/login");
		req2.setRemoteAddr("10.1.0.2");
		req2.setContentType("application/json;charset=UTF-8");
		req2.setContent(body2.getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse res2 = new MockHttpServletResponse();
		filter.doFilter(req2, res2, chain);
		assertThat(res2.getStatus()).isEqualTo(200);

		// 3rd attempt from a brand-new IP targeting the same normalized email is throttled with ceil Retry-After.
		MockHttpServletRequest req3 = new MockHttpServletRequest("POST", "/api/auth/login");
		req3.setRemoteAddr("10.1.0.3");
		req3.setContentType("application/json");
		req3.setContent("{\"email\":\"VICTIM@EXAMPLE.COM\",\"password\":\"bad3\"}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse res3 = new MockHttpServletResponse();
		filter.doFilter(req3, res3, chain);
		assertThat(res3.getStatus()).isEqualTo(429);
		assertThat(res3.getContentType()).isEqualTo("application/problem+json");
		assertThat(Integer.parseInt(res3.getHeader("Retry-After"))).isEqualTo(30);

		// A different account from 10.1.0.3 is unaffected.
		FilterChain otherChain = mock(FilterChain.class);
		MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/auth/login");
		other.setRemoteAddr("10.1.0.3");
		other.setContentType("application/json");
		other.setContent("{\"email\":\"other@example.com\",\"password\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse otherRes = new MockHttpServletResponse();
		filter.doFilter(other, otherRes, otherChain);
		assertThat(otherRes.getStatus()).isEqualTo(200);
		verify(otherChain, times(1)).doFilter(any(), any());
	}

	@Test
	void loginMalformedAndOversizedPayloadsPassThroughIntact() throws ServletException, IOException {
		RateLimitingFilter filter = new RateLimitingFilter(true, 10, 10, 60, 10, 10, 60);
		FilterChain chain = mock(FilterChain.class);

		String malformed = "{\"email\":\"alice@example.com\",}";
		String oversized = "{\"email\":\"alice@example.com\",\"password\":\"" + "x".repeat(20_000) + "\"}";
		doAnswer(inv -> {
			HttpServletRequest passed = inv.getArgument(0);
			String read = new String(passed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(read).isIn(malformed, oversized);
			return null;
		}).when(chain).doFilter(any(), any());

		MockHttpServletRequest badReq = new MockHttpServletRequest("POST", "/api/auth/login");
		badReq.setRemoteAddr("10.2.0.1");
		badReq.setContentType("application/json");
		badReq.setContent(malformed.getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse badRes = new MockHttpServletResponse();
		filter.doFilter(badReq, badRes, chain);
		assertThat(badRes.getStatus()).isEqualTo(200);

		MockHttpServletRequest bigReq = new MockHttpServletRequest("POST", "/api/auth/login");
		bigReq.setRemoteAddr("10.2.0.2");
		bigReq.setContentType("application/json");
		bigReq.setContent(oversized.getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse bigRes = new MockHttpServletResponse();
		filter.doFilter(bigReq, bigRes, chain);
		assertThat(bigRes.getStatus()).isEqualTo(200);
		verify(chain, times(2)).doFilter(any(), any());
	}

	@Test
	void throttledIpShortCircuitsBeforeCreatingLoginAccountBucket() throws ServletException, IOException {
		RateLimitingFilter filter = new RateLimitingFilter(
			true, 1, 1, 60, 5, 5, 60, 10, 10, 60, 100, 600);
		FilterChain chain = mock(FilterChain.class);

		MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/auth/register");
		first.setRemoteAddr("10.3.0.1");
		filter.doFilter(first, new MockHttpServletResponse(), chain);
		assertThat(filter.bucketCount()).isEqualTo(1);

		MockHttpServletRequest secondLogin = new MockHttpServletRequest("POST", "/api/auth/login");
		secondLogin.setRemoteAddr("10.3.0.1");
		secondLogin.setContentType("application/json");
		secondLogin.setContent("{\"email\":\"flood@example.com\",\"password\":\"pw\"}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse secondRes = new MockHttpServletResponse();
		filter.doFilter(secondLogin, secondRes, chain);

		assertThat(secondRes.getStatus()).isEqualTo(429);
		assertThat(filter.bucketCount()).isEqualTo(1);
	}
}
