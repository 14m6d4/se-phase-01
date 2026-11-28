package vn.edu.uet.cinema.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class RateLimitingFilter extends OncePerRequestFilter {

	public static final int DEFAULT_MAX_BUCKETS = 20_000;
	public static final long DEFAULT_BUCKET_TTL_SECONDS = 600L;
	public static final long DEFAULT_LOGIN_ACCOUNT_CAPACITY = 5L;
	public static final long DEFAULT_LOGIN_ACCOUNT_REFILL_TOKENS = 5L;
	public static final long DEFAULT_LOGIN_ACCOUNT_DURATION_SECONDS = 60L;

	private static final int MAX_LOGIN_BODY_PEEK_BYTES = 16 * 1024;
	private static final int MAX_RAW_EMAIL_KEY_LENGTH = 320;

	private final boolean enabled;
	private final long authCapacity;
	private final long authRefillTokens;
	private final Duration authDuration;
	private final long loginAccountCapacity;
	private final long loginAccountRefillTokens;
	private final Duration loginAccountDuration;
	private final long apiCapacity;
	private final long apiRefillTokens;
	private final Duration apiDuration;
	private final LongSupplier nanoTimeSource;

	private final BoundedLruCache<String, TokenBucket> buckets;

	public RateLimitingFilter(boolean enabled, long authCapacity, long authRefillTokens, long authDurationSeconds,
		long apiCapacity, long apiRefillTokens, long apiDurationSeconds) {
		this(enabled, authCapacity, authRefillTokens, authDurationSeconds,
			DEFAULT_LOGIN_ACCOUNT_CAPACITY, DEFAULT_LOGIN_ACCOUNT_REFILL_TOKENS, DEFAULT_LOGIN_ACCOUNT_DURATION_SECONDS,
			apiCapacity, apiRefillTokens, apiDurationSeconds,
			DEFAULT_MAX_BUCKETS, DEFAULT_BUCKET_TTL_SECONDS);
	}

	public RateLimitingFilter(boolean enabled,
		long authCapacity, long authRefillTokens, long authDurationSeconds,
		long loginAccountCapacity, long loginAccountRefillTokens, long loginAccountDurationSeconds,
		long apiCapacity, long apiRefillTokens, long apiDurationSeconds,
		int maxBuckets, long bucketTtlSeconds) {
		this(enabled, authCapacity, authRefillTokens, authDurationSeconds,
			loginAccountCapacity, loginAccountRefillTokens, loginAccountDurationSeconds,
			apiCapacity, apiRefillTokens, apiDurationSeconds,
			maxBuckets, bucketTtlSeconds, System::nanoTime);
	}

	RateLimitingFilter(boolean enabled,
		long authCapacity, long authRefillTokens, long authDurationSeconds,
		long loginAccountCapacity, long loginAccountRefillTokens, long loginAccountDurationSeconds,
		long apiCapacity, long apiRefillTokens, long apiDurationSeconds,
		int maxBuckets, long bucketTtlSeconds, LongSupplier nanoTimeSource) {
		if (bucketTtlSeconds <= 0) {
			throw new IllegalArgumentException("bucketTtlSeconds must be positive");
		}
		this.enabled = enabled;
		this.authCapacity = authCapacity;
		this.authRefillTokens = authRefillTokens;
		this.authDuration = Duration.ofSeconds(authDurationSeconds);
		this.loginAccountCapacity = loginAccountCapacity;
		this.loginAccountRefillTokens = loginAccountRefillTokens;
		this.loginAccountDuration = Duration.ofSeconds(loginAccountDurationSeconds);
		this.apiCapacity = apiCapacity;
		this.apiRefillTokens = apiRefillTokens;
		this.apiDuration = Duration.ofSeconds(apiDurationSeconds);
		this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
		this.buckets = new BoundedLruCache<>(maxBuckets, Duration.ofSeconds(bucketTtlSeconds).toNanos(), nanoTimeSource);
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
		HttpServletRequest requestToPass = request;

		if (path.startsWith("/api/auth/")) {
			String clientIp = ClientIpResolver.resolve(request);
			TokenBucket ipBucket = buckets.getOrCreate("auth:ip:" + clientIp,
				k -> new TokenBucket(authCapacity, authRefillTokens, authDuration, nanoTimeSource));
			TokenBucket.ConsumptionResult ipResult = ipBucket.tryConsume(1);
			if (!ipResult.allowed()) {
				writeRateLimitProblem(response, ipResult.retryAfterSeconds());
				return;
			}
			// Check per-account limit only after IP passes, preventing unauthenticated JSON/bucket churn from a throttled IP.
			if (isLoginPost(request, path)) {
				LoginBodyPeek peek = peekLoginBody(request);
				requestToPass = peek.request();
				if (peek.normalizedEmailKey() != null) {
					TokenBucket accountBucket = buckets.getOrCreate("login:acct:" + peek.normalizedEmailKey(),
						k -> new TokenBucket(loginAccountCapacity, loginAccountRefillTokens, loginAccountDuration,
							nanoTimeSource));
					TokenBucket.ConsumptionResult accountResult = accountBucket.tryConsume(1);
					if (!accountResult.allowed()) {
						writeRateLimitProblem(response, accountResult.retryAfterSeconds());
						return;
					}
				}
			}
		} else {
			String key = resolveApiKey(request);
			TokenBucket bucket = buckets.getOrCreate(key,
				k -> new TokenBucket(apiCapacity, apiRefillTokens, apiDuration, nanoTimeSource));
			TokenBucket.ConsumptionResult result = bucket.tryConsume(1);
			if (!result.allowed()) {
				writeRateLimitProblem(response, result.retryAfterSeconds());
				return;
			}
		}

		filterChain.doFilter(requestToPass, response);
	}

	int bucketCount() {
		return buckets.size();
	}

	private String resolveApiKey(HttpServletRequest request) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
			return "user:" + auth.getName();
		}
		return "api:ip:" + ClientIpResolver.resolve(request);
	}

	private static boolean isLoginPost(HttpServletRequest request, String path) {
		return "POST".equalsIgnoreCase(request.getMethod()) && "/api/auth/login".equals(path);
	}

	private static LoginBodyPeek peekLoginBody(HttpServletRequest request) throws IOException {
		String contentType = request.getContentType();
		if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("json")) {
			return new LoginBodyPeek(request, null);
		}
		ServletInputStream raw = request.getInputStream();
		byte[] prefix = raw.readNBytes(MAX_LOGIN_BODY_PEEK_BYTES + 1);
		if (prefix.length > MAX_LOGIN_BODY_PEEK_BYTES) {
			// Body exceeds bounded peek size: replay prefix + remaining stream without buffering full payload in RAM.
			return new LoginBodyPeek(new ReplayableBodyRequest(request, prefix, raw), null);
		}
		HttpServletRequest wrapped = new ReplayableBodyRequest(request, prefix, null);
		String emailKey = prefix.length == 0 ? null : extractNormalizedEmailKey(prefix);
		return new LoginBodyPeek(wrapped, emailKey);
	}

	private static String extractNormalizedEmailKey(byte[] body) {
		try {
			JsonNode root = JsonMapper.shared().readTree(body);
			if (root == null || !root.isObject()) {
				return null;
			}
			JsonNode emailNode = root.get("email");
			if (emailNode == null || !emailNode.isString()) {
				return null;
			}
			String rawEmail = emailNode.asString();
			if (rawEmail == null || rawEmail.indexOf('\0') >= 0) {
				return null;
			}
			String normalized = rawEmail.strip().toLowerCase(Locale.ROOT);
			if (normalized.isEmpty()) {
				return null;
			}
			if (normalized.length() > MAX_RAW_EMAIL_KEY_LENGTH) {
				return sha256Hex(normalized);
			}
			return normalized;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private void writeRateLimitProblem(HttpServletResponse response, long retryAfterSeconds) throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
		response.getWriter().write(
			"{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Too many requests. Please retry after %d seconds.\"}"
				.formatted(retryAfterSeconds));
	}

	private record LoginBodyPeek(HttpServletRequest request, String normalizedEmailKey) {
	}

	private static final class ReplayableBodyRequest extends HttpServletRequestWrapper {

		private final InputStream stream;
		private ServletInputStream cachedServletStream;

		ReplayableBodyRequest(HttpServletRequest request, byte[] prefix, InputStream tail) {
			super(request);
			ByteArrayInputStream head = new ByteArrayInputStream(prefix);
			this.stream = tail == null ? head : new SequenceInputStream(head, tail);
		}

		@Override
		public ServletInputStream getInputStream() {
			if (cachedServletStream == null) {
				cachedServletStream = new ReplayableServletInputStream(stream);
			}
			return cachedServletStream;
		}

		@Override
		public BufferedReader getReader() throws IOException {
			String encoding = getCharacterEncoding();
			String charsetName = encoding != null && !encoding.isBlank() ? encoding : StandardCharsets.UTF_8.name();
			return new BufferedReader(new InputStreamReader(getInputStream(), charsetName));
		}
	}

	private static final class ReplayableServletInputStream extends ServletInputStream {

		private final InputStream delegate;
		private boolean finished;

		ReplayableServletInputStream(InputStream delegate) {
			this.delegate = delegate;
		}

		@Override
		public int read() throws IOException {
			int b = delegate.read();
			if (b == -1) {
				finished = true;
			}
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int count = delegate.read(b, off, len);
			if (count == -1) {
				finished = true;
			}
			return count;
		}

		@Override
		public int available() throws IOException {
			return delegate.available();
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}

		@Override
		public boolean isFinished() {
			return finished;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener listener) {
			throw new UnsupportedOperationException("Async read is not supported");
		}
	}
}
