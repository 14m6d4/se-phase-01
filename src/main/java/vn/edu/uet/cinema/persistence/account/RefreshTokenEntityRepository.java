package vn.edu.uet.cinema.persistence.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshTokenEntityRepository extends JpaRepository<RefreshTokenEntity, UUID> {

	Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshTokenEntity t set t.revokedAt = :revokedAt, t.replacedByTokenId = :replacedBy "
		+ "where t.id = :id and t.revokedAt is null")
	int markRotated(@Param("id") UUID id, @Param("revokedAt") Instant revokedAt,
		@Param("replacedBy") UUID replacedBy);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshTokenEntity t set t.revokedAt = :revokedAt "
		+ "where t.familyId = :familyId and t.revokedAt is null")
	int revokeByFamilyId(@Param("familyId") UUID familyId, @Param("revokedAt") Instant revokedAt);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshTokenEntity t set t.revokedAt = :revokedAt "
		+ "where t.accountId = :accountId and t.familyId = :familyId and t.revokedAt is null")
	int revokeByAccountIdAndFamilyId(@Param("accountId") UUID accountId, @Param("familyId") UUID familyId,
		@Param("revokedAt") Instant revokedAt);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshTokenEntity t set t.revokedAt = :revokedAt "
		+ "where t.accountId = :accountId and t.revokedAt is null")
	int revokeByAccountId(@Param("accountId") UUID accountId, @Param("revokedAt") Instant revokedAt);
}
