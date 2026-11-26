package vn.edu.uet.cinema.business.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenServiceTest {

	private final InMemoryAccounts accounts = new InMemoryAccounts();
	private final InMemoryRefreshTokens refreshTokens = new InMemoryRefreshTokens();
	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
	private final RefreshTokenService service = new RefreshTokenService(refreshTokens, accounts, Duration.ofDays(7),
		clock);

	@Test
	void issuesHashedRefreshTokenAndRotatesWithinSameFamily() {
		Account alice = saveAccount("alice@example.com");

		RefreshTokenService.IssuedRefreshToken issued = service.issue(alice.id());
		String firstRaw = issued.rawRefreshToken();
		assertThat(firstRaw).isNotBlank();
		assertThat(refreshTokens.findByTokenHash(firstRaw)).isEmpty();
		RefreshToken storedFirst = refreshTokens.findByTokenHash(RefreshTokenService.hash(firstRaw)).orElseThrow();
		assertThat(storedFirst.accountId()).isEqualTo(alice.id());
		assertThat(storedFirst.familyId()).isEqualTo(issued.refreshToken().familyId());
		assertThat(storedFirst.isActive(clock.instant())).isTrue();

		clock.advance(Duration.ofMinutes(15));
		RefreshTokenService.RotatedRefreshToken rotated = service.rotate(firstRaw);
		assertThat(rotated.account()).isEqualTo(alice);
		assertThat(rotated.rawRefreshToken()).isNotBlank().isNotEqualTo(firstRaw);
		assertThat(rotated.refreshToken().familyId()).isEqualTo(storedFirst.familyId());

		RefreshToken revokedFirst = refreshTokens.findByTokenHash(RefreshTokenService.hash(firstRaw)).orElseThrow();
		assertThat(revokedFirst.isRevoked()).isTrue();
		assertThat(revokedFirst.replacedByTokenId()).isEqualTo(rotated.refreshToken().id());
	}

	@Test
	void replayingRotatedRefreshTokenRevokesEntireFamily() {
		Account alice = saveAccount("alice@example.com");
		String firstRaw = service.issue(alice.id()).rawRefreshToken();

		RefreshTokenService.RotatedRefreshToken second = service.rotate(firstRaw);
		String secondRaw = second.rawRefreshToken();

		// Presenting the already-used firstRaw triggers reuse detection and revokes secondRaw too.
		assertThatThrownBy(() -> service.rotate(firstRaw))
			.isInstanceOf(InvalidCredentialsException.class)
			.hasMessageContaining("revoked");

		assertThatThrownBy(() -> service.rotate(secondRaw))
			.isInstanceOf(InvalidCredentialsException.class)
			.hasMessageContaining("revoked");
	}

	@Test
	void rejectsUnknownBlankOrExpiredRefreshToken() {
		Account alice = saveAccount("alice@example.com");
		String raw = service.issue(alice.id()).rawRefreshToken();

		assertThatThrownBy(() -> service.rotate(null)).isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> service.rotate("   ")).isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> service.rotate("not-a-known-token")).isInstanceOf(InvalidCredentialsException.class);

		clock.advance(Duration.ofDays(7));
		assertThatThrownBy(() -> service.rotate(raw))
			.isInstanceOf(InvalidCredentialsException.class)
			.hasMessageContaining("expired");
	}

	@Test
	void revokeForAccountRevokesOnlyOwningAccountsFamily() {
		Account alice = saveAccount("alice@example.com");
		Account bob = saveAccount("bob@example.com");

		String aliceToken = service.issue(alice.id()).rawRefreshToken();
		String bobToken = service.issue(bob.id()).rawRefreshToken();

		// Alice cannot revoke Bob's refresh token.
		service.revokeForAccount(alice.id(), bobToken);
		assertThat(service.rotate(bobToken).account()).isEqualTo(bob);

		// Alice can revoke her own refresh token.
		service.revokeForAccount(alice.id(), aliceToken);
		assertThatThrownBy(() -> service.rotate(aliceToken))
			.isInstanceOf(InvalidCredentialsException.class)
			.hasMessageContaining("revoked");
	}

	@Test
	void revokeFamilyForAccountRevokesOnlyMatchingAccountAndFamily() {
		Account alice = saveAccount("alice@example.com");
		Account bob = saveAccount("bob@example.com");

		RefreshTokenService.IssuedRefreshToken aliceSession1 = service.issue(alice.id());
		RefreshTokenService.IssuedRefreshToken aliceSession2 = service.issue(alice.id());
		RefreshTokenService.IssuedRefreshToken bobSession = service.issue(bob.id());

		// Alice cannot revoke Bob's family even if she guesses its UUID.
		service.revokeFamilyForAccount(alice.id(), bobSession.refreshToken().familyId());
		assertThat(service.rotate(bobSession.rawRefreshToken()).account()).isEqualTo(bob);

		// Revoking Alice's session 1 leaves session 2 active.
		service.revokeFamilyForAccount(alice.id(), aliceSession1.refreshToken().familyId());
		assertThatThrownBy(() -> service.rotate(aliceSession1.rawRefreshToken()))
			.isInstanceOf(InvalidCredentialsException.class)
			.hasMessageContaining("revoked");
		assertThat(service.rotate(aliceSession2.rawRefreshToken()).account()).isEqualTo(alice);
	}

	@Test
	void revokeAllForAccountRevokesEveryActiveSession() {
		Account alice = saveAccount("alice@example.com");
		String device1 = service.issue(alice.id()).rawRefreshToken();
		String device2 = service.issue(alice.id()).rawRefreshToken();

		assertThat(service.revokeAllForAccount(alice.id())).isEqualTo(2);
		assertThatThrownBy(() -> service.rotate(device1)).isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> service.rotate(device2)).isInstanceOf(InvalidCredentialsException.class);
	}

	private Account saveAccount(String email) {
		return accounts.save(new Account(UUID.randomUUID(), email, "hashed", "Test User", Role.CUSTOMER));
	}

	private static final class MutableClock extends Clock {

		private Instant current;

		MutableClock(Instant current) {
			this.current = current;
		}

		void advance(Duration delta) {
			this.current = this.current.plus(delta);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}
	}
}
