package vn.edu.uet.cinema.config;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Thread-safe bounded LRU cache with idle TTL eviction.
 * Time units of {@code ttl} and {@code timeSource} must match (for example, both nanoseconds or both milliseconds).
 */
final class BoundedLruCache<K, V> {

	private final int maxEntries;
	private final long ttl;
	private final LongSupplier timeSource;
	private final LinkedHashMap<K, CacheEntry<V>> map;
	private final Object lock = new Object();

	BoundedLruCache(int maxEntries, long ttl, LongSupplier timeSource) {
		if (maxEntries < 1) {
			throw new IllegalArgumentException("maxEntries must be positive");
		}
		if (ttl <= 0) {
			throw new IllegalArgumentException("ttl must be positive");
		}
		this.maxEntries = maxEntries;
		this.ttl = ttl;
		this.timeSource = Objects.requireNonNull(timeSource, "timeSource must not be null");
		this.map = new LinkedHashMap<>(Math.min(maxEntries, 256), 0.75f, true);
	}

	V get(K key) {
		if (key == null) {
			return null;
		}
		synchronized (lock) {
			long now = timeSource.getAsLong();
			CacheEntry<V> entry = map.get(key);
			if (entry == null) {
				return null;
			}
			if (isExpired(entry, now)) {
				map.remove(key);
				return null;
			}
			entry.lastTouched = now;
			return entry.value;
		}
	}

	V getOrCreate(K key, Function<? super K, ? extends V> factory) {
		Objects.requireNonNull(key, "key must not be null");
		Objects.requireNonNull(factory, "factory must not be null");
		synchronized (lock) {
			long now = timeSource.getAsLong();
			evictExpiredLocked(now);
			CacheEntry<V> entry = map.get(key);
			if (entry != null) {
				entry.lastTouched = now;
				return entry.value;
			}
			V value = Objects.requireNonNull(factory.apply(key), "factory must not return null");
			map.put(key, new CacheEntry<>(value, now));
			evictOverflowLocked();
			return value;
		}
	}

	void putIfAbsent(K key, V value) {
		Objects.requireNonNull(key, "key must not be null");
		Objects.requireNonNull(value, "value must not be null");
		synchronized (lock) {
			long now = timeSource.getAsLong();
			evictExpiredLocked(now);
			CacheEntry<V> existing = map.get(key);
			if (existing != null) {
				existing.lastTouched = now;
				return;
			}
			map.put(key, new CacheEntry<>(value, now));
			evictOverflowLocked();
		}
	}

	boolean remove(K key, V expectedValue) {
		if (key == null || expectedValue == null) {
			return false;
		}
		synchronized (lock) {
			CacheEntry<V> existing = map.get(key);
			if (existing != null && Objects.equals(existing.value, expectedValue)) {
				map.remove(key);
				return true;
			}
			return false;
		}
	}

	int size() {
		synchronized (lock) {
			return map.size();
		}
	}

	int pruneExpired() {
		synchronized (lock) {
			return evictExpiredLocked(timeSource.getAsLong());
		}
	}

	private boolean isExpired(CacheEntry<V> entry, long now) {
		return now - entry.lastTouched >= ttl;
	}

	private int evictExpiredLocked(long now) {
		int removed = 0;
		Iterator<Map.Entry<K, CacheEntry<V>>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<K, CacheEntry<V>> oldest = it.next();
			if (isExpired(oldest.getValue(), now)) {
				it.remove();
				removed++;
			} else {
				// LinkedHashMap(accessOrder=true) with monotonic now inside lock keeps entries ordered by lastTouched.
				break;
			}
		}
		return removed;
	}

	private void evictOverflowLocked() {
		if (map.size() <= maxEntries) {
			return;
		}
		Iterator<Map.Entry<K, CacheEntry<V>>> it = map.entrySet().iterator();
		while (map.size() > maxEntries && it.hasNext()) {
			it.next();
			it.remove();
		}
	}

	private static final class CacheEntry<V> {

		final V value;
		long lastTouched;

		CacheEntry(V value, long lastTouched) {
			this.value = value;
			this.lastTouched = lastTouched;
		}
	}
}
