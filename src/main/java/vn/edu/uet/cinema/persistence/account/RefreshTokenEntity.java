package vn.edu.uet.cinema.persistence.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import vn.edu.uet.cinema.business.account.RefreshToken;

@Entity
@Table(name = "refresh_token")
class RefreshTokenEntity {

	@Id
	private UUID id;
	@Column(name = "account_id", nullable = false)
	private UUID accountId;
	@Column(name = "family_id", nullable = false)
	private UUID familyId;
	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;
	@Column(name = "revoked_at")
	private Instant revokedAt;
	@Column(name = "replaced_by_token_id")
	private UUID replacedByTokenId;

	protected RefreshTokenEntity() {
	}

	static RefreshTokenEntity from(RefreshToken token) {
		RefreshTokenEntity entity = new RefreshTokenEntity();
		entity.id = token.id();
		entity.accountId = token.accountId();
		entity.familyId = token.familyId();
		entity.tokenHash = token.tokenHash();
		entity.createdAt = token.createdAt();
		entity.expiresAt = token.expiresAt();
		entity.revokedAt = token.revokedAt();
		entity.replacedByTokenId = token.replacedByTokenId();
		return entity;
	}

	RefreshToken toRefreshToken() {
		return new RefreshToken(id, accountId, familyId, tokenHash, createdAt, expiresAt, revokedAt, replacedByTokenId);
	}
}
