package vn.edu.uet.cinema.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Micrometer metrics for diagnosing booking latency (DB queries, commits, lock waits) and HTTP 409 conflicts (O1).
 * Lives in {@code config} so the business layer remains framework-free (ADR 0001).
 */
public class BookingMetrics {

	public static final String OPERATION_TIMER = "cinema.booking.operation";
	public static final String DB_QUERY_TIMER = "cinema.booking.db.query";
	public static final String DB_COMMIT_TIMER = "cinema.booking.db.commit";
	public static final String LOCK_WAIT_TIMER = "cinema.booking.lock.wait";
	public static final String DEADLOCK_RETRY_COUNTER = "cinema.booking.deadlock.retries";
	public static final String HTTP_CONFLICT_COUNTER = "cinema.http.conflicts";

	private static final BookingMetrics NOOP = new BookingMetrics(null);

	private final MeterRegistry registry;

	public BookingMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	public static BookingMetrics noop() {
		return NOOP;
	}

	public void recordOperation(String operation, String strategy, String outcome, long durationNanos) {
		if (registry == null) {
			return;
		}
		Timer.builder(OPERATION_TIMER)
			.description("End-to-end persistence duration of a booking inventory operation")
			.tag("operation", operation)
			.tag("strategy", strategy)
			.tag("outcome", outcome)
			.register(registry)
			.record(Math.max(durationNanos, 0L), TimeUnit.NANOSECONDS);
	}

	public void recordDbQuery(String operation, String step, String strategy, long durationNanos) {
		if (registry == null) {
			return;
		}
		Timer.builder(DB_QUERY_TIMER)
			.description("Database query execution time per booking step")
			.tag("operation", operation)
			.tag("step", step)
			.tag("strategy", strategy)
			.register(registry)
			.record(Math.max(durationNanos, 0L), TimeUnit.NANOSECONDS);
	}

	public void recordCommit(String operation, String strategy, long durationNanos) {
		if (registry == null) {
			return;
		}
		Timer.builder(DB_COMMIT_TIMER)
			.description("Database transaction commit duration for booking writes")
			.tag("operation", operation)
			.tag("strategy", strategy)
			.register(registry)
			.record(Math.max(durationNanos, 0L), TimeUnit.NANOSECONDS);
	}

	public void recordLockWait(String lock, String strategy, long durationNanos) {
		if (registry == null) {
			return;
		}
		Timer.builder(LOCK_WAIT_TIMER)
			.description("Lock acquisition and wait duration during booking")
			.tag("lock", lock)
			.tag("strategy", strategy)
			.register(registry)
			.record(Math.max(durationNanos, 0L), TimeUnit.NANOSECONDS);
	}

	public void recordDeadlockRetry(String strategy, String result) {
		if (registry == null) {
			return;
		}
		Counter.builder(DEADLOCK_RETRY_COUNTER)
			.description("Deadlock retries during seat hold acquisition")
			.tag("strategy", strategy)
			.tag("result", result)
			.register(registry)
			.increment();
	}

	public void recordConflict(String reason) {
		if (registry == null) {
			return;
		}
		Counter.builder(HTTP_CONFLICT_COUNTER)
			.description("HTTP 409 Conflict responses grouped by business reason")
			.tag("reason", reason)
			.register(registry)
			.increment();
	}

	public <T> T timeDbQuery(String operation, String step, String strategy, Supplier<T> query) {
		if (registry == null) {
			return query.get();
		}
		long start = System.nanoTime();
		try {
			return query.get();
		} finally {
			recordDbQuery(operation, step, strategy, System.nanoTime() - start);
		}
	}

	public void timeDbQuery(String operation, String step, String strategy, Runnable query) {
		if (registry == null) {
			query.run();
			return;
		}
		long start = System.nanoTime();
		try {
			query.run();
		} finally {
			recordDbQuery(operation, step, strategy, System.nanoTime() - start);
		}
	}

	public <T> T timeLockQuery(String lock, String operation, String step, String strategy, Supplier<T> query) {
		if (registry == null) {
			return query.get();
		}
		long start = System.nanoTime();
		try {
			return query.get();
		} finally {
			long elapsed = System.nanoTime() - start;
			recordDbQuery(operation, step, strategy, elapsed);
			recordLockWait(lock, strategy, elapsed);
		}
	}
}
