package vn.edu.uet.cinema.business.booking;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AntiHoardingTest {

	private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
	private final AntiHoardingGuard guard = new AntiHoardingGuard(Duration.ofSeconds(5));
	private final UUID customerId = UUID.randomUUID();

	@Test
	void newCustomerCanCreateHoldImmediately() {
		assertThatCode(() -> guard.checkHoldAllowed(customerId, NOW)).doesNotThrowAnyException();
	}

	@Test
	void abandonTriggersCooldownWindow() {
		guard.recordAbandon(customerId, NOW);

		// Within 5s cooldown: blocked
		assertThatThrownBy(() -> guard.checkHoldAllowed(customerId, NOW.plusSeconds(2)))
			.isInstanceOf(SeatHoardingException.class)
			.hasMessageContaining("cooldown");

		// After 5s cooldown: permitted
		assertThatCode(() -> guard.checkHoldAllowed(customerId, NOW.plusSeconds(6)))
			.doesNotThrowAnyException();
	}

	@Test
	void repeatedAbandonsTriggerLongerPenaltyCooldown() {
		guard.recordAbandon(customerId, NOW);
		guard.recordAbandon(customerId, NOW.plusSeconds(10));
		guard.recordAbandon(customerId, NOW.plusSeconds(20)); // 3rd abandon in window

		// Should receive 2-minute penalty
		assertThatThrownBy(() -> guard.checkHoldAllowed(customerId, NOW.plusSeconds(30)))
			.isInstanceOf(SeatHoardingException.class);

		// After 2 minutes: permitted
		assertThatCode(() -> guard.checkHoldAllowed(customerId, NOW.plusSeconds(145)))
			.doesNotThrowAnyException();
	}
}
