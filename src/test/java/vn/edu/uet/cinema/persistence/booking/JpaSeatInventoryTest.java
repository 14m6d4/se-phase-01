package vn.edu.uet.cinema.persistence.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.business.showtime.inventory.BookedSeat;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;
import vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome;

/**
 * Deadlock retry verification without dynamic bytecode instrumentation (ADR 0001, C3).
 * A real deadlock between lapsed-Hold deletes is verified against PostgreSQL in BookingPersistenceTest;
 * the retry logic in JpaSeatInventory is tested here via standard Java dynamic proxies.
 */
class JpaSeatInventoryTest {

	private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

	private final Booking booking = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
		BookingStatus.PENDING, NOW, NOW.plusSeconds(600), List.of(new BookedSeat(UUID.randomUUID(), 100_000)));

	@Test
	void bookingThatLosesADeadlockIsRetriedOnce() {
		AtomicInteger saveCalls = new AtomicInteger();
		BookingEntityRepository jpa = (BookingEntityRepository) Proxy.newProxyInstance(
			BookingEntityRepository.class.getClassLoader(),
			new Class<?>[] { BookingEntityRepository.class },
			(proxy, method, args) -> {
				if ("saveAndFlush".equals(method.getName())) {
					int count = saveCalls.incrementAndGet();
					if (count == 1) {
						throw new CannotAcquireLockException("deadlock detected");
					}
					return args[0];
				}
				if ("lockShowtimeStatus".equals(method.getName())) {
					return "SCHEDULED";
				}
				return null;
			}
		);
		JpaSeatInventory inventory = new JpaSeatInventory(jpa, TransactionOperations.withoutTransaction());

		assertThat(inventory.hold(booking, NOW)).isEqualTo(new HoldOutcome.Held(booking));
		assertThat(saveCalls.get()).isEqualTo(2);
	}

	@Test
	void secondDeadlockIsAConflictThatDoesNotClaimTheSeatsAreTaken() {
		AtomicInteger saveCalls = new AtomicInteger();
		BookingEntityRepository jpa = (BookingEntityRepository) Proxy.newProxyInstance(
			BookingEntityRepository.class.getClassLoader(),
			new Class<?>[] { BookingEntityRepository.class },
			(proxy, method, args) -> {
				if ("saveAndFlush".equals(method.getName())) {
					saveCalls.incrementAndGet();
					throw new CannotAcquireLockException("deadlock detected");
				}
				if ("lockShowtimeStatus".equals(method.getName())) {
					return "SCHEDULED";
				}
				return null;
			}
		);
		JpaSeatInventory inventory = new JpaSeatInventory(jpa, TransactionOperations.withoutTransaction());

		assertThat(inventory.hold(booking, NOW)).isEqualTo(new HoldOutcome.Busy());
		assertThat(saveCalls.get()).isEqualTo(2);
	}
}

