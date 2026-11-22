package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
