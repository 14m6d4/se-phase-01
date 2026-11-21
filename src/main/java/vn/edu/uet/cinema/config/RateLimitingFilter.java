package vn.edu.uet.cinema.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitingFilter extends OncePerRequestFilter {

	private final boolean enabled;
	private final long authCapacity;
	private final long authRefillTokens;
	private final Duration authDuration;
	private final long apiCapacity;
	private final long apiRefillTokens;
	private final Duration apiDuration;

	private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

	public RateLimitingFilter(boolean enabled, long authCapacity, long authRefillTokens, long authDurationSeconds,
		long apiCapacity, long apiRefillTokens, long apiDurationSeconds) {
		this.enabled = enabled;
		this.authCapacity = authCapacity;
		this.authRefillTokens = authRefillTokens;
		this.authDuration = Duration.ofSeconds(authDurationSeconds);
		this.apiCapacity = apiCapacity;
		this.apiRefillTokens = apiRefillTokens;
		this.apiDuration = Duration.ofSeconds(apiDurationSeconds);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!enabled) {
			return true;
		}
		String path = request.getRequestURI();
		return !path.startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {

		String path = request.getRequestURI();
		TokenBucket.ConsumptionResult result;

		if (path.startsWith("/api/auth/")) {
			String clientIp = getClientIp(request);
			TokenBucket bucket = buckets.computeIfAbsent("auth:" + clientIp,
				k -> new TokenBucket(authCapacity, authRefillTokens, authDuration));
			result = bucket.tryConsume(1);
		} else {
			String key = resolveApiKey(request);
			TokenBucket bucket = buckets.computeIfAbsent(key,
				k -> new TokenBucket(apiCapacity, apiRefillTokens, apiDuration));
			result = bucket.tryConsume(1);
		}

		if (!result.allowed()) {
			writeRateLimitProblem(response, result.retryAfterSeconds());
			return;
		}

		filterChain.doFilter(request, response);
	}

	private String resolveApiKey(HttpServletRequest request) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
			return "user:" + auth.getName();
		}
		return "api:ip:" + getClientIp(request);
	}

	private String getClientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			int comma = forwarded.indexOf(',');
			return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
		}
		return request.getRemoteAddr();
	}

	private void writeRateLimitProblem(HttpServletResponse response, long retryAfterSeconds) throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
		response.getWriter().write(
			"{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Too many requests. Please retry after %d seconds.\"}"
				.formatted(retryAfterSeconds));
	}
}
