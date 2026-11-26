package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import vn.edu.uet.cinema.business.account.InMemoryTokenBlacklist;

/**
 * Verifies that zero clock skew prevents logged-out tokens from being resurrected during [exp, exp + 60s] (S2).
 */
class SecurityConfigJwtDecoderTest {

	@Test
	void loggedOutTokenCannotResurrectAtExpPlus30Seconds() {
		Instant issuedAt = Instant.parse("2026-11-26T10:00:00Z");
		Instant expiresAt = issuedAt.plus(Duration.ofMinutes(15));
		MutableClock clock = new MutableClock(issuedAt);

		SecurityConfig security = new SecurityConfig();
		SecretKey key = security.jwtKey("s2-clock-skew-test-secret-with-at-least-32-bytes");
		JwtEncoder encoder = security.jwtEncoder(key);
		InMemoryTokenBlacklist blacklist = new InMemoryTokenBlacklist(100, clock);
		JwtDecoder decoder = security.jwtDecoder(key, blacklist, clock);

		JwtClaimsSet claims = JwtClaimsSet.builder()
			.id(UUID.randomUUID().toString())
			.subject(UUID.randomUUID().toString())
			.issuedAt(issuedAt)
			.expiresAt(expiresAt)
			.claim(SecurityConfig.ROLES_CLAIM, List.of("CUSTOMER"))
			.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();

		// 1. Valid before logout (and warms the decoder cache).
		Jwt decoded = decoder.decode(token);
		assertThat(decoded.getId()).isEqualTo(claims.getId());

		// 2. Logout blacklists the token; immediate rejection while unexpired.
		clock.set(issuedAt.plus(Duration.ofMinutes(1)));
		blacklist.revoke(SecurityConfig.tokenKey(decoded), decoded.getExpiresAt());
		assertThatThrownBy(() -> decoder.decode(token))
			.isInstanceOf(JwtException.class);

		// 3. At exp + 30s (within Nimbus's default 60s clock skew), blacklist entry is expired,
		//    yet the token MUST remain rejected rather than resurrecting.
		clock.set(expiresAt.plusSeconds(30));
		assertThat(blacklist.isRevoked(SecurityConfig.tokenKey(decoded), clock.instant())).isFalse();
		assertThatThrownBy(() -> decoder.decode(token))
			.isInstanceOf(JwtException.class);
	}

	private static final class MutableClock extends Clock {

		private Instant current;

		MutableClock(Instant current) {
			this.current = current;
		}

		void set(Instant instant) {
			this.current = instant;
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
