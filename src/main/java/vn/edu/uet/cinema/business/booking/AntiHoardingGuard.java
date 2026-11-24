package vn.edu.uet.cinema.business.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guard against seat hoarding through automated hold-and-release loops, repeated abandons, or
 * multi-account Sybil holds from the same client/IP (C2).
 * Plain Java class without framework annotations (ADR 0001).
 */
public class AntiHoardingGuard {

	public static final Duration DEFAULT_COOLDOWN_AFTER_ABANDON = Duration.ofSeconds(5);
	public static final Duration ABANDON_WINDOW = Duration.ofMinutes(10);
	public static final int MAX_ABANDONS_IN_WINDOW = 3;
	public static final Duration REPEATED_ABANDON_PENALTY = Duration.ofMinutes(2);
	public static final int DEFAULT_MAX_ACTIVE_HOLDS_PER_CLIENT = 3;
	public static final int DEFAULT_MAX_TRACKED_ENTRIES = 10_000;

	private final boolean enabled;
	private final Duration cooldownAfterAbandon;
	private final int maxAbandonsInWindow;
	private final Duration repeatedAbandonPenalty;
	private final int maxActiveHoldsPerClient;
	private final int maxTrackedEntries;

	private final ConcurrentHashMap<UUID, Instant> cooldowns = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, List<Instant>> abandonHistory = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, List<ActiveHold>> activeHoldsByClient = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, String> clientByBookingId = new ConcurrentHashMap<>();

	public AntiHoardingGuard() {
		this(true, DEFAULT_COOLDOWN_AFTER_ABANDON);
	}

	public AntiHoardingGuard(boolean enabled) {
		this(enabled, DEFAULT_COOLDOWN_AFTER_ABANDON);
	}

	public AntiHoardingGuard(Duration cooldownAfterAbandon) {
		this(true, cooldownAfterAbandon);
	}

	public AntiHoardingGuard(boolean enabled, Duration cooldownAfterAbandon) {
		this(enabled, cooldownAfterAbandon, MAX_ABANDONS_IN_WINDOW, REPEATED_ABANDON_PENALTY,
			DEFAULT_MAX_ACTIVE_HOLDS_PER_CLIENT, DEFAULT_MAX_TRACKED_ENTRIES);
	}

	public AntiHoardingGuard(boolean enabled, Duration cooldownAfterAbandon, int maxAbandonsInWindow,
		Duration repeatedAbandonPenalty, int maxActiveHoldsPerClient) {
		this(enabled, cooldownAfterAbandon, maxAbandonsInWindow, repeatedAbandonPenalty,
			maxActiveHoldsPerClient, DEFAULT_MAX_TRACKED_ENTRIES);
	}

	public AntiHoardingGuard(boolean enabled, Duration cooldownAfterAbandon, int maxAbandonsInWindow,
		Duration repeatedAbandonPenalty, int maxActiveHoldsPerClient, int maxTrackedEntries) {
		this.enabled = enabled;
		this.cooldownAfterAbandon = cooldownAfterAbandon != null ? cooldownAfterAbandon : DEFAULT_COOLDOWN_AFTER_ABANDON;
		this.maxAbandonsInWindow = maxAbandonsInWindow < 1 ? MAX_ABANDONS_IN_WINDOW : maxAbandonsInWindow;
		this.repeatedAbandonPenalty = repeatedAbandonPenalty != null
			? repeatedAbandonPenalty : REPEATED_ABANDON_PENALTY;
		this.maxActiveHoldsPerClient = Math.max(0, maxActiveHoldsPerClient);
		this.maxTrackedEntries = maxTrackedEntries < 1 ? DEFAULT_MAX_TRACKED_ENTRIES : maxTrackedEntries;
	}

	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Checks whether the customer is currently permitted to create a new hold.
	 *
	 * @throws SeatHoardingException if the customer is in cooldown
	 */
	public void checkHoldAllowed(UUID customerId, Instant now) {
		checkHoldAllowed(customerId, null, now);
	}

	/**
	 * Checks both customer cooldown and per-client active concurrent hold cap before creating a hold.
	 *
	 * @throws SeatHoardingException if the customer is in cooldown or the client has reached its active hold cap
	 */
	public void checkHoldAllowed(UUID customerId, String clientKey, Instant now) {
		if (!enabled) {
			return;
		}
		Instant cooldownUntil = cooldowns.get(customerId);
		if (cooldownUntil != null) {
			if (now.isBefore(cooldownUntil)) {
				long remainingSeconds = Duration.between(now, cooldownUntil).toSeconds() + 1;
				throw new SeatHoardingException(
					"Seat hoarding protection: account is in cooldown for " + remainingSeconds + " more second(s)");
			}
			cooldowns.remove(customerId, cooldownUntil);
		}
		if (maxActiveHoldsPerClient > 0 && clientKey != null && !clientKey.isBlank()) {
			int[] activeCount = {0};
			activeHoldsByClient.computeIfPresent(clientKey, (key, holds) -> {
				List<ActiveHold> active = new ArrayList<>(holds.size());
				for (ActiveHold hold : holds) {
					if (hold.expiresAt().isAfter(now)) {
						active.add(hold);
					} else {
						clientByBookingId.remove(hold.bookingId(), key);
					}
				}
				activeCount[0] = active.size();
				return active.isEmpty() ? null : active;
			});
			if (activeCount[0] >= maxActiveHoldsPerClient) {
				throw new SeatHoardingException(
					"Seat hoarding protection: client has reached maximum concurrent active holds ("
						+ maxActiveHoldsPerClient + ")");
			}
		}
	}

	/**
	 * Tracks a newly created Pending hold against the originating client/IP key.
	 */
	public void recordHoldCreated(UUID customerId, String clientKey, UUID bookingId, Instant expiresAt, Instant now) {
		if (!enabled || clientKey == null || clientKey.isBlank() || bookingId == null || expiresAt == null) {
			return;
		}
		evictIfOversized(now);
		clientByBookingId.put(bookingId, clientKey);
		activeHoldsByClient.compute(clientKey, (key, existing) -> {
			List<ActiveHold> updated = existing == null ? new ArrayList<>(1) : new ArrayList<>(existing.size() + 1);
			if (existing != null) {
				for (ActiveHold hold : existing) {
					if (!hold.bookingId().equals(bookingId) && hold.expiresAt().isAfter(now)) {
						updated.add(hold);
					} else if (!hold.expiresAt().isAfter(now)) {
						clientByBookingId.remove(hold.bookingId(), key);
					}
				}
			}
			updated.add(new ActiveHold(bookingId, customerId, expiresAt));
			return updated;
		});
	}

	/**
	 * Releases the active hold slot when a hold is paid or abandoned so legitimate traffic on the same IP is unblocked.
	 */
	public void recordHoldReleased(UUID bookingId) {
		if (bookingId == null) {
			return;
		}
		String clientKey = clientByBookingId.remove(bookingId);
		if (clientKey == null) {
			return;
		}
		activeHoldsByClient.computeIfPresent(clientKey, (key, existing) -> {
			List<ActiveHold> updated = new ArrayList<>(existing.size());
			for (ActiveHold hold : existing) {
				if (!hold.bookingId().equals(bookingId)) {
					updated.add(hold);
				}
			}
			return updated.isEmpty() ? null : updated;
		});
	}

	/**
	 * Records an explicit abandon by the customer, applying immediate cooldown and tracking repeated churn.
	 */
	public void recordAbandon(UUID customerId, Instant now) {
		if (!enabled) {
			return;
		}
		evictIfOversized(now);
		abandonHistory.compute(customerId, (id, history) -> {
			List<Instant> updated = history == null ? new ArrayList<>() : new ArrayList<>(history);
			Instant cutoff = now.minus(ABANDON_WINDOW);
			updated.removeIf(ts -> ts.isBefore(cutoff));
			updated.add(now);

			if (updated.size() >= maxAbandonsInWindow) {
				cooldowns.put(customerId, now.plus(repeatedAbandonPenalty));
			} else {
				cooldowns.put(customerId, now.plus(cooldownAfterAbandon));
			}
			return updated;
		});
	}

	/** Resets any active cooldown and active hold tracking for testing or administrative actions. */
	public void clear(UUID customerId) {
		cooldowns.remove(customerId);
		abandonHistory.remove(customerId);
		for (String clientKey : List.copyOf(activeHoldsByClient.keySet())) {
			activeHoldsByClient.computeIfPresent(clientKey, (key, existing) -> {
				List<ActiveHold> remaining = new ArrayList<>(existing.size());
				for (ActiveHold hold : existing) {
					if (customerId.equals(hold.customerId())) {
						clientByBookingId.remove(hold.bookingId(), key);
					} else {
						remaining.add(hold);
					}
				}
				return remaining.isEmpty() ? null : remaining;
			});
		}
	}

	int trackedCustomerCooldowns() {
		return cooldowns.size();
	}

	int trackedAbandonHistories() {
		return abandonHistory.size();
	}

	int trackedClients() {
		return activeHoldsByClient.size();
	}

	private void evictIfOversized(Instant now) {
		if (cooldowns.size() >= maxTrackedEntries) {
			cooldowns.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
			if (cooldowns.size() >= maxTrackedEntries) {
				Iterator<UUID> it = cooldowns.keySet().iterator();
				while (cooldowns.size() >= maxTrackedEntries && it.hasNext()) {
					cooldowns.remove(it.next());
				}
			}
		}
		if (abandonHistory.size() >= maxTrackedEntries) {
			Instant cutoff = now.minus(ABANDON_WINDOW);
			for (Map.Entry<UUID, List<Instant>> entry : abandonHistory.entrySet()) {
				List<Instant> history = entry.getValue();
				if (history.isEmpty() || history.get(history.size() - 1).isBefore(cutoff)) {
					abandonHistory.remove(entry.getKey(), history);
				}
			}
			if (abandonHistory.size() >= maxTrackedEntries) {
				Iterator<UUID> it = abandonHistory.keySet().iterator();
				while (abandonHistory.size() >= maxTrackedEntries && it.hasNext()) {
					abandonHistory.remove(it.next());
				}
			}
		}
		if (activeHoldsByClient.size() >= maxTrackedEntries || clientByBookingId.size() >= maxTrackedEntries) {
			for (String key : List.copyOf(activeHoldsByClient.keySet())) {
				activeHoldsByClient.computeIfPresent(key, (k, holds) -> {
					List<ActiveHold> active = new ArrayList<>(holds.size());
					for (ActiveHold hold : holds) {
						if (hold.expiresAt().isAfter(now)) {
							active.add(hold);
						} else {
							clientByBookingId.remove(hold.bookingId(), k);
						}
					}
					return active.isEmpty() ? null : active;
				});
			}
			if (activeHoldsByClient.size() >= maxTrackedEntries) {
				Iterator<String> it = activeHoldsByClient.keySet().iterator();
				while (activeHoldsByClient.size() >= maxTrackedEntries && it.hasNext()) {
					String key = it.next();
					List<ActiveHold> removed = activeHoldsByClient.remove(key);
					if (removed != null) {
						for (ActiveHold hold : removed) {
							clientByBookingId.remove(hold.bookingId(), key);
						}
					}
				}
			}
		}
	}

	private record ActiveHold(UUID bookingId, UUID customerId, Instant expiresAt) {
	}
}
