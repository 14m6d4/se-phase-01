package vn.edu.uet.cinema.persistence.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uet.cinema.business.account.RefreshToken;
import vn.edu.uet.cinema.business.account.RefreshTokenRepository;

@Repository
class JpaRefreshTokenRepository implements RefreshTokenRepository {

	private final RefreshTokenEntityRepository jpa;

	JpaRefreshTokenRepository(RefreshTokenEntityRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional
	public RefreshToken save(RefreshToken token) {
		return jpa.saveAndFlush(RefreshTokenEntity.from(token)).toRefreshToken();
	}

	@Override
	public Optional<RefreshToken> findByTokenHash(String tokenHash) {
		return jpa.findByTokenHash(tokenHash).map(RefreshTokenEntity::toRefreshToken);
	}

	@Override
	@Transactional
	public boolean rotate(UUID previousTokenId, RefreshToken replacement, Instant revokedAt) {
		int updated = jpa.markRotated(previousTokenId, revokedAt, replacement.id());
		if (updated == 0) {
			return false;
		}
		jpa.saveAndFlush(RefreshTokenEntity.from(replacement));
		return true;
	}

	@Override
	@Transactional
	public int revokeByFamilyId(UUID familyId, Instant revokedAt) {
		return jpa.revokeByFamilyId(familyId, revokedAt);
	}

	@Override
	@Transactional
	public int revokeByAccountIdAndFamilyId(UUID accountId, UUID familyId, Instant revokedAt) {
		return jpa.revokeByAccountIdAndFamilyId(accountId, familyId, revokedAt);
	}

	@Override
	@Transactional
	public int revokeByAccountId(UUID accountId, Instant revokedAt) {
		return jpa.revokeByAccountId(accountId, revokedAt);
	}
}
