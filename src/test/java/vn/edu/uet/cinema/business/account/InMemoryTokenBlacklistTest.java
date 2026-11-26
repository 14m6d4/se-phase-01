package vn.edu.uet.cinema.business.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryTokenBlacklistTest {

	@Test
	void revokedTokenIsRejectedUntilExpirationThenAutomaticallyCleared() {
		Instant now = Instant.parse("2026-11-26T10:00:00Z");
		MutableClock clock = new MutableClock(now);
		InMemoryTokenBlacklist blacklist = new InMemoryTokenBlacklist(100, clock);

		blacklist.revoke("jti-1", now.plus(Duration.ofMinutes(15)));

		assertThat(blacklist.isRevoked("jti-1", now)).isTrue();
		assertThat(blacklist.isRevoked("jti-1", now.plus(Duration.ofMinutes(14)))).isTrue();
		assertThat(blacklist.isRevoked("other-jti", now)).isFalse();

		// At or after expiration, lookup returns false and lazily removes the entry.
		assertThat(blacklist.isRevoked("jti-1", now.plus(Duration.ofMinutes(15)))).isFalse();
		assertThat(blacklist.size()).isZero();
	}

	@Test
	void ignoresBlankOrAlreadyExpiredTokens() {
		Instant now = Instant.parse("2026-11-26T10:00:00Z");
		InMemoryTokenBlacklist blacklist = new InMemoryTokenBlacklist(100, new MutableClock(now));

		blacklist.revoke(null, now.plusSeconds(60));
		blacklist.revoke("   ", now.plusSeconds(60));
		blacklist.revoke("already-expired", now.minusSeconds(1));

		assertThat(blacklist.size()).isZero();
		assertThat(blacklist.isRevoked("already-expired", now)).isFalse();
	}

	@Test
	void evictsExpiredFirstAndEarliestExpiringWhenOverCapacity() {
		Instant now = Instant.parse("2026-11-26T10:00:00Z");
		MutableClock clock = new MutableClock(now);
		InMemoryTokenBlacklist blacklist = new InMemoryTokenBlacklist(2, clock);

		blacklist.revoke("soon", now.plusSeconds(10));
		blacklist.revoke("later", now.plusSeconds(60));
		assertThat(blacklist.size()).isEqualTo(2);

		// Adding a third while both are unexpired evicts the earliest-expiring ("soon").
		blacklist.revoke("latest", now.plusSeconds(120));
		assertThat(blacklist.size()).isEqualTo(2);
		assertThat(blacklist.isRevoked("soon", now)).isFalse();
		assertThat(blacklist.isRevoked("later", now)).isTrue();
		assertThat(blacklist.isRevoked("latest", now)).isTrue();

		// Once expired, pruning removes expired entries before inserting.
		clock.advance(Duration.ofSeconds(70));
		blacklist.revoke("fresh", clock.instant().plusSeconds(60));
		assertThat(blacklist.size()).isEqualTo(2);
		assertThat(blacklist.isRevoked("later", clock.instant())).isFalse();
		assertThat(blacklist.isRevoked("latest", clock.instant())).isTrue();
		assertThat(blacklist.isRevoked("fresh", clock.instant())).isTrue();
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
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}
	}
}
