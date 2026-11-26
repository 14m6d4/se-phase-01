package vn.edu.uet.cinema.business.account;

import java.time.Instant;
import java.util.UUID;

/** Immutable representation of a persisted refresh token (S2). */
public record RefreshToken(
	UUID id,
	UUID accountId,
	UUID familyId,
	String tokenHash,
	Instant createdAt,
	Instant expiresAt,
	Instant revokedAt,
	UUID replacedByTokenId) {

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isExpired(Instant now) {
		return !expiresAt.isAfter(now);
	}

	public boolean isActive(Instant now) {
		return !isRevoked() && !isExpired(now);
	}
}
