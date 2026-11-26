package vn.edu.uet.cinema.business.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for revocable, rotating refresh tokens (S2).
 * Plain Java interface without framework annotations (ADR 0001).
 */
public interface RefreshTokenRepository {

	RefreshToken save(RefreshToken token);

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Atomically marks {@code previousTokenId} as revoked (only if not yet revoked) and persists {@code replacement}.
	 *
	 * @return {@code true} when rotation succeeded, {@code false} when {@code previousTokenId} was already revoked
	 */
	boolean rotate(UUID previousTokenId, RefreshToken replacement, Instant revokedAt);

	int revokeByFamilyId(UUID familyId, Instant revokedAt);

	int revokeByAccountIdAndFamilyId(UUID accountId, UUID familyId, Instant revokedAt);

	int revokeByAccountId(UUID accountId, Instant revokedAt);
}
