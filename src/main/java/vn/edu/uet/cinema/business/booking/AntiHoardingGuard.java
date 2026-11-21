package vn.edu.uet.cinema.business.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guard against seat hoarding through automated hold-and-release loops or repeated abandons (C2).
 * Plain Java class without framework annotations (ADR 0001).
 */
public class AntiHoardingGuard {

	public static final Duration DEFAULT_COOLDOWN_AFTER_ABANDON = Duration.ofSeconds(5);
	public static final Duration ABANDON_WINDOW = Duration.ofMinutes(10);
	public static final int MAX_ABANDONS_IN_WINDOW = 3;
	public static final Duration REPEATED_ABANDON_PENALTY = Duration.ofMinutes(2);

	private final Duration cooldownAfterAbandon;
	private final ConcurrentHashMap<UUID, Instant> cooldowns = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, List<Instant>> abandonHistory = new ConcurrentHashMap<>();

	public AntiHoardingGuard() {
		this(DEFAULT_COOLDOWN_AFTER_ABANDON);
	}

	public AntiHoardingGuard(Duration cooldownAfterAbandon) {
		this.cooldownAfterAbandon = cooldownAfterAbandon;
	}

	/**
	 * Checks whether the customer is currently permitted to create a new hold.
	 *
	 * @throws SeatHoardingException if the customer is in cooldown
	 */
	public void checkHoldAllowed(UUID customerId, Instant now) {
		Instant cooldownUntil = cooldowns.get(customerId);
		if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
			long remainingSeconds = Duration.between(now, cooldownUntil).toSeconds() + 1;
			throw new SeatHoardingException(
				"Seat hoarding protection: account is in cooldown for " + remainingSeconds + " more second(s)");
		}
	}

	/**
	 * Records an explicit abandon by the customer, applying immediate cooldown and tracking repeated churn.
	 */
	public void recordAbandon(UUID customerId, Instant now) {
		abandonHistory.compute(customerId, (id, history) -> {
			List<Instant> updated = history == null ? new ArrayList<>() : new ArrayList<>(history);
			Instant cutoff = now.minus(ABANDON_WINDOW);
			updated.removeIf(ts -> ts.isBefore(cutoff));
			updated.add(now);

			if (updated.size() >= MAX_ABANDONS_IN_WINDOW) {
				// Repeated churn penalty
				cooldowns.put(customerId, now.plus(REPEATED_ABANDON_PENALTY));
			} else {
				// Base cooldown after abandon
				cooldowns.put(customerId, now.plus(cooldownAfterAbandon));
			}
			return updated;
		});
	}

	/** Resets any active cooldown for testing or administrative actions. */
	public void clear(UUID customerId) {
		cooldowns.remove(customerId);
		abandonHistory.remove(customerId);
	}
}
