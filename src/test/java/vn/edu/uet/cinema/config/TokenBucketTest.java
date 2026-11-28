package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

	@Test
	void invalidConstructorArgumentsThrow() {
		assertThatThrownBy(() -> new TokenBucket(0, 10, Duration.ofSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TokenBucket(10, 0, Duration.ofSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TokenBucket(10, 10, Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TokenBucket(10, 10, Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void allowsTokensUpToCapacity() {
		TokenBucket bucket = new TokenBucket(3, 1, Duration.ofSeconds(10));

		assertThat(bucket.tryConsume(1).allowed()).isTrue();
		assertThat(bucket.tryConsume(1).allowed()).isTrue();
		assertThat(bucket.tryConsume(1).allowed()).isTrue();

		TokenBucket.ConsumptionResult rejected = bucket.tryConsume(1);
		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void retryAfterRoundsUpFractionalSeconds() {
		AtomicLong nanos = new AtomicLong(1_000_000_000L);
		// 1 token every 2 seconds -> after 0.1s, 1.9s remain -> ceil(1.9) == 2s (not truncated to 1s).
		TokenBucket bucket = new TokenBucket(1, 1, Duration.ofSeconds(2), nanos::get);
		assertThat(bucket.tryConsume(1).allowed()).isTrue();

		nanos.addAndGet(100_000_000L);
		TokenBucket.ConsumptionResult rejectedAtOnePointNine = bucket.tryConsume(1);
		assertThat(rejectedAtOnePointNine.allowed()).isFalse();
		assertThat(rejectedAtOnePointNine.retryAfterSeconds()).isEqualTo(2L);

		// Just over 1 second remaining -> ceil == 2s; exact 1 second remaining -> 1s.
		nanos.addAndGet(899_999_999L); // total elapsed = 0.999999999s -> 1.000000001s remaining
		assertThat(bucket.tryConsume(1).retryAfterSeconds()).isEqualTo(2L);

		nanos.addAndGet(1L); // total elapsed = 1.0s -> 1.0s remaining
		assertThat(bucket.tryConsume(1).retryAfterSeconds()).isEqualTo(1L);
	}

	@Test
	void zeroOrNegativeRequestedTokensAreAlwaysAllowed() {
		TokenBucket bucket = new TokenBucket(1, 1, Duration.ofSeconds(10));
		assertThat(bucket.tryConsume(0).allowed()).isTrue();
		assertThat(bucket.tryConsume(-5).allowed()).isTrue();
	}

	@Test
	void refillsTokensOverTime() throws InterruptedException {
		// 10 tokens per second -> ~1 token every 100ms
		TokenBucket bucket = new TokenBucket(2, 10, Duration.ofSeconds(1));

		assertThat(bucket.tryConsume(2).allowed()).isTrue();
		assertThat(bucket.tryConsume(1).allowed()).isFalse();

		Thread.sleep(150);

		// After 150ms, at least 1 token should have refilled
		assertThat(bucket.tryConsume(1).allowed()).isTrue();
	}

	@Test
	void highThroughputBenchmarkOverhead() {
		TokenBucket bucket = new TokenBucket(1_000_000, 1_000_000, Duration.ofSeconds(1));
		int iterations = 100_000;
		long start = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			bucket.tryConsume(1);
		}
		long elapsedNanos = System.nanoTime() - start;
		long nanosPerOp = elapsedNanos / iterations;
		// Must take less than 10 microseconds per operation in test environment
		assertThat(nanosPerOp).isLessThan(10_000);
	}
}
