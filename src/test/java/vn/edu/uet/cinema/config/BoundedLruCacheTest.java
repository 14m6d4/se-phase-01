package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedLruCacheTest {

	@Test
	void evictsOnlyLeastRecentlyUsedEntryAtCapacityWithoutWipingAllEntries() {
		AtomicLong now = new AtomicLong(1_000L);
		BoundedLruCache<String, String> cache = new BoundedLruCache<>(3, 60_000L, now::get);

		cache.putIfAbsent("k1", "v1");
		cache.putIfAbsent("k2", "v2");
		cache.putIfAbsent("k3", "v3");

		// Touch k1 so k2 becomes the least recently used entry.
		assertThat(cache.get("k1")).isEqualTo("v1");

		cache.putIfAbsent("k4", "v4");

		assertThat(cache.size()).isEqualTo(3);
		assertThat(cache.get("k2")).isNull();
		assertThat(cache.get("k1")).isEqualTo("v1");
		assertThat(cache.get("k3")).isEqualTo("v3");
		assertThat(cache.get("k4")).isEqualTo("v4");
	}

	@Test
	void expiresIdleEntriesAfterTtl() {
		AtomicLong now = new AtomicLong(0L);
		BoundedLruCache<String, Integer> cache = new BoundedLruCache<>(10, 100L, now::get);
		AtomicInteger created = new AtomicInteger();

		assertThat(cache.getOrCreate("a", k -> created.incrementAndGet())).isEqualTo(1);
		now.set(50L);
		assertThat(cache.getOrCreate("b", k -> created.incrementAndGet())).isEqualTo(2);

		// At t=100, "a" is expired (100 - 0 >= 100), while "b" is still alive (100 - 50 < 100).
		now.set(100L);
		assertThat(cache.get("a")).isNull();
		assertThat(cache.get("b")).isEqualTo(2);
		assertThat(cache.getOrCreate("a", k -> created.incrementAndGet())).isEqualTo(3);
	}
}
