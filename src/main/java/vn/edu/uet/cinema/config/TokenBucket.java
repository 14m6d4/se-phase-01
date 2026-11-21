package vn.edu.uet.cinema.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class TokenBucket {

	private final long capacity;
	private final double refillRatePerNano;
	private double tokens;
	private long lastRefillNanos;

	public TokenBucket(long capacity, long refillTokens, Duration refillDuration) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive");
		}
		if (refillTokens <= 0) {
			throw new IllegalArgumentException("refillTokens must be positive");
		}
		if (refillDuration.isNegative() || refillDuration.isZero()) {
			throw new IllegalArgumentException("refillDuration must be positive");
		}
		this.capacity = capacity;
		this.refillRatePerNano = (double) refillTokens / refillDuration.toNanos();
		this.tokens = capacity;
		this.lastRefillNanos = System.nanoTime();
	}

	public synchronized ConsumptionResult tryConsume(long tokensRequested) {
		if (tokensRequested <= 0) {
			return ConsumptionResult.permit();
		}
		long now = System.nanoTime();
		long elapsedNanos = Math.max(0, now - lastRefillNanos);
		tokens = Math.min(capacity, tokens + elapsedNanos * refillRatePerNano);
		lastRefillNanos = now;

		if (tokens >= tokensRequested) {
			tokens -= tokensRequested;
			return ConsumptionResult.permit();
		}
		double missingTokens = tokensRequested - tokens;
		long waitNanos = (long) Math.ceil(missingTokens / refillRatePerNano);
		long waitSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(waitNanos));
		return ConsumptionResult.reject(waitSeconds);
	}

	public synchronized double availableTokens() {
		long now = System.nanoTime();
		long elapsedNanos = Math.max(0, now - lastRefillNanos);
		return Math.min(capacity, tokens + elapsedNanos * refillRatePerNano);
	}

	public record ConsumptionResult(boolean allowed, long retryAfterSeconds) {
		public static ConsumptionResult permit() {
			return new ConsumptionResult(true, 0);
		}

		public static ConsumptionResult reject(long retryAfterSeconds) {
			return new ConsumptionResult(false, retryAfterSeconds);
		}
	}
}
