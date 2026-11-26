package vn.edu.uet.cinema.api.account;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;
import vn.edu.uet.cinema.business.account.Account;
import vn.edu.uet.cinema.config.SecurityConfig;

@Component
class TokenIssuer {

	static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

	private final JwtEncoder encoder;
	private final Duration ttl;

	TokenIssuer(JwtEncoder encoder) {
		this(encoder, DEFAULT_TTL);
	}

	@Autowired
	TokenIssuer(JwtEncoder encoder, @Value("${app.jwt.access-token-ttl:15m}") Duration ttl) {
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw new IllegalArgumentException("Access token TTL must be positive");
		}
		this.encoder = encoder;
		this.ttl = ttl;
	}

	Duration ttl() {
		return ttl;
	}

	String issue(Account account, UUID familyId) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
			.id(UUID.randomUUID().toString())
			.subject(account.id().toString())
			.issuedAt(now)
			.expiresAt(now.plus(ttl))
			.claim(SecurityConfig.ROLES_CLAIM, List.of(account.role().name()));
		if (familyId != null) {
			claims.claim(SecurityConfig.FAMILY_ID_CLAIM, familyId.toString());
		}
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}
}
