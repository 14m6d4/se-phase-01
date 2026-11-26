package vn.edu.uet.cinema.persistence.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.account.Account;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.account.RefreshToken;
import vn.edu.uet.cinema.business.account.RefreshTokenRepository;

/**
 * PostgreSQL persistence and concurrency tests for rotating refresh tokens (Issue #16 / S2).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefreshTokenPersistenceTest {

	@Autowired AccountService accounts;
	@Autowired RefreshTokenRepository refreshTokens;

	@Test
	void savesFindsRotatesAndRevokesFamilyInPostgres() {
		Account user = accounts.registerCustomer("rt-pg-" + UUID.randomUUID() + "@example.com", "password123", "RT");
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		UUID familyId = UUID.randomUUID();

		RefreshToken first = new RefreshToken(UUID.randomUUID(), user.id(), familyId,
			sha256Hex("first-" + UUID.randomUUID()), now, now.plus(7, ChronoUnit.DAYS), null, null);
		refreshTokens.save(first);

		assertThat(refreshTokens.findByTokenHash(first.tokenHash())).contains(first);

		RefreshToken second = new RefreshToken(UUID.randomUUID(), user.id(), familyId,
			sha256Hex("second-" + UUID.randomUUID()), now.plusSeconds(10), now.plus(7, ChronoUnit.DAYS), null, null);
		boolean rotated = refreshTokens.rotate(first.id(), second, now.plusSeconds(10));
		assertThat(rotated).isTrue();

		RefreshToken reloadedFirst = refreshTokens.findByTokenHash(first.tokenHash()).orElseThrow();
		assertThat(reloadedFirst.isRevoked()).isTrue();
		assertThat(reloadedFirst.replacedByTokenId()).isEqualTo(second.id());

		// Second rotation of the already-rotated token fails atomically.
		RefreshToken duplicate = new RefreshToken(UUID.randomUUID(), user.id(), familyId,
			sha256Hex("dup-" + UUID.randomUUID()), now.plusSeconds(20), now.plus(7, ChronoUnit.DAYS), null, null);
		assertThat(refreshTokens.rotate(first.id(), duplicate, now.plusSeconds(20))).isFalse();
		assertThat(refreshTokens.findByTokenHash(duplicate.tokenHash())).isEmpty();

		// Revoking the family revokes the active replacement token.
		assertThat(refreshTokens.revokeByFamilyId(familyId, now.plusSeconds(30))).isEqualTo(1);
		assertThat(refreshTokens.findByTokenHash(second.tokenHash()).orElseThrow().isRevoked()).isTrue();
	}

	@Test
	void concurrentRotationsOfSameTokenAllowExactlyOneWinner() throws Exception {
		Account user = accounts.registerCustomer("rt-race-" + UUID.randomUUID() + "@example.com", "password123", "Race");
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		UUID familyId = UUID.randomUUID();
		RefreshToken initial = new RefreshToken(UUID.randomUUID(), user.id(), familyId,
			sha256Hex("race-" + UUID.randomUUID()), now, now.plus(7, ChronoUnit.DAYS), null, null);
		refreshTokens.save(initial);

		int threads = 6;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int idx = i;
				futures.add(pool.submit(() -> {
					RefreshToken next = new RefreshToken(UUID.randomUUID(), user.id(), familyId,
						sha256Hex("race-next-" + idx + "-" + UUID.randomUUID()), now.plusSeconds(1),
						now.plus(7, ChronoUnit.DAYS), null, null);
					ready.countDown();
					assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
					return refreshTokens.rotate(initial.id(), next, now.plusSeconds(1));
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			go.countDown();

			long winners = 0;
			for (Future<Boolean> future : futures) {
				if (future.get(15, TimeUnit.SECONDS)) {
					winners++;
				}
			}
			assertThat(winners).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	private static String sha256Hex(String seed) {
		try {
			var digest = java.security.MessageDigest.getInstance("SHA-256");
			return java.util.HexFormat.of().formatHex(digest.digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
