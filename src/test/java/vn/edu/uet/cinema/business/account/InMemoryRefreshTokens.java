package vn.edu.uet.cinema.business.account;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-memory {@link RefreshTokenRepository} for business tests. */
public class InMemoryRefreshTokens implements RefreshTokenRepository {

	private final Map<UUID, RefreshToken> byId = new HashMap<>();

	public int size() {
		return byId.size();
	}

	@Override
	public synchronized RefreshToken save(RefreshToken token) {
		Objects.requireNonNull(token, "token must not be null");
		for (RefreshToken existing : byId.values()) {
			if (existing.tokenHash().equals(token.tokenHash()) && !existing.id().equals(token.id())) {
				throw new IllegalStateException("Duplicate refresh token hash");
			}
		}
		byId.put(token.id(), token);
		return token;
	}

	@Override
	public synchronized Optional<RefreshToken> findByTokenHash(String tokenHash) {
		if (tokenHash == null) {
			return Optional.empty();
		}
		return byId.values().stream()
			.filter(token -> token.tokenHash().equals(tokenHash))
			.findFirst();
	}

	@Override
	public synchronized boolean rotate(UUID previousTokenId, RefreshToken replacement, Instant revokedAt) {
		RefreshToken previous = byId.get(previousTokenId);
		if (previous == null || previous.isRevoked()) {
			return false;
		}
		byId.put(previousTokenId, new RefreshToken(
			previous.id(),
			previous.accountId(),
			previous.familyId(),
			previous.tokenHash(),
			previous.createdAt(),
			previous.expiresAt(),
			revokedAt,
			replacement.id()));
		save(replacement);
		return true;
	}

	@Override
	public synchronized int revokeByFamilyId(UUID familyId, Instant revokedAt) {
		int updated = 0;
		for (RefreshToken existing : byId.values().stream().toList()) {
			if (existing.familyId().equals(familyId) && !existing.isRevoked()) {
				byId.put(existing.id(), new RefreshToken(
					existing.id(),
					existing.accountId(),
					existing.familyId(),
					existing.tokenHash(),
					existing.createdAt(),
					existing.expiresAt(),
					revokedAt,
					existing.replacedByTokenId()));
				updated++;
			}
		}
		return updated;
	}

	@Override
	public synchronized int revokeByAccountIdAndFamilyId(UUID accountId, UUID familyId, Instant revokedAt) {
		int updated = 0;
		for (RefreshToken existing : byId.values().stream().toList()) {
			if (existing.accountId().equals(accountId) && existing.familyId().equals(familyId) && !existing.isRevoked()) {
				byId.put(existing.id(), new RefreshToken(
					existing.id(),
					existing.accountId(),
					existing.familyId(),
					existing.tokenHash(),
					existing.createdAt(),
					existing.expiresAt(),
					revokedAt,
					existing.replacedByTokenId()));
				updated++;
			}
		}
		return updated;
	}

	@Override
	public synchronized int revokeByAccountId(UUID accountId, Instant revokedAt) {
		int updated = 0;
		for (RefreshToken existing : byId.values().stream().toList()) {
			if (existing.accountId().equals(accountId) && !existing.isRevoked()) {
				byId.put(existing.id(), new RefreshToken(
					existing.id(),
					existing.accountId(),
					existing.familyId(),
					existing.tokenHash(),
					existing.createdAt(),
					existing.expiresAt(),
					revokedAt,
					existing.replacedByTokenId()));
				updated++;
			}
		}
		return updated;
	}
}
