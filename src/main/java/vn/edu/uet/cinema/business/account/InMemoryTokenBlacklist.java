package vn.edu.uet.cinema.business.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, bounded in-memory access token blacklist keyed by JWT identifier ({@code jti}) (S2).
 * Entries are retained until the token's original expiration and pruned lazily as well as when approaching capacity.
 * Plain Java class without framework annotations (ADR 0001).
 */
public class InMemoryTokenBlacklist implements TokenBlacklist {

	public static final int DEFAULT_MAX_ENTRIES = 100_000;
	public static final Duration FALLBACK_TTL = Duration.ofMinutes(15);

	private final ConcurrentHashMap<String, Instant> revokedTokens = new ConcurrentHashMap<>();
	private final int maxEntries;
	private final Clock clock;

	public InMemoryTokenBlacklist() {
		this(DEFAULT_MAX_ENTRIES, Clock.systemUTC());
	}

	public InMemoryTokenBlacklist(int maxEntries, Clock clock) {
		this.maxEntries = maxEntries < 1 ? DEFAULT_MAX_ENTRIES : maxEntries;
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	@Override
	public void revoke(String tokenId, Instant expiresAt) {
		if (tokenId == null || tokenId.isBlank()) {
			return;
		}
		Instant now = clock.instant();
		Instant effectiveExpiry = expiresAt != null ? expiresAt : now.plus(FALLBACK_TTL);
		if (!effectiveExpiry.isAfter(now)) {
			return;
		}
		pruneIfOversized(now);
		revokedTokens.put(tokenId, effectiveExpiry);
	}

	@Override
	public boolean isRevoked(String tokenId, Instant now) {
		if (revokedTokens.isEmpty() || tokenId == null || tokenId.isBlank()) {
			return false;
		}
		Instant expiresAt = revokedTokens.get(tokenId);
		if (expiresAt == null) {
			return false;
		}
		if (!expiresAt.isAfter(now)) {
			revokedTokens.remove(tokenId, expiresAt);
			return false;
		}
		return true;
	}

	public int size() {
		return revokedTokens.size();
	}

	public void pruneExpired(Instant now) {
		revokedTokens.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
	}

	private void pruneIfOversized(Instant now) {
		if (revokedTokens.size() < maxEntries) {
			return;
		}
		pruneExpired(now);
		if (revokedTokens.size() >= maxEntries) {
			int excess = revokedTokens.size() - maxEntries + 1;
			revokedTokens.entrySet().stream()
				.sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
				.limit(excess)
				.forEach(entry -> revokedTokens.remove(entry.getKey(), entry.getValue()));
		}
	}
}
