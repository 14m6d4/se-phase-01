package vn.edu.uet.cinema.business.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, rotates, and revokes opaque refresh tokens with token-family reuse detection (S2).
 * Plain Java service without framework annotations (ADR 0001).
 */
public class RefreshTokenService {

	public static final Duration DEFAULT_TTL = Duration.ofDays(7);
	private static final int TOKEN_BYTES = 32;

	private final RefreshTokenRepository refreshTokens;
	private final AccountRepository accounts;
	private final Duration ttl;
	private final Clock clock;
	private final SecureRandom random;

	public RefreshTokenService(RefreshTokenRepository refreshTokens, AccountRepository accounts) {
		this(refreshTokens, accounts, DEFAULT_TTL, Clock.systemUTC());
	}

	public RefreshTokenService(RefreshTokenRepository refreshTokens, AccountRepository accounts, Duration ttl) {
		this(refreshTokens, accounts, ttl, Clock.systemUTC());
	}

	public RefreshTokenService(RefreshTokenRepository refreshTokens, AccountRepository accounts, Duration ttl,
		Clock clock) {
		this.refreshTokens = Objects.requireNonNull(refreshTokens, "refreshTokens must not be null");
		this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw new IllegalArgumentException("Refresh token TTL must be positive");
		}
		this.ttl = ttl;
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
		this.random = new SecureRandom();
	}

	/** Creates a fresh refresh token starting a new token family for a login session. */
	public IssuedRefreshToken issue(UUID accountId) {
		Objects.requireNonNull(accountId, "accountId must not be null");
		Instant now = clock.instant();
		String rawToken = generateRawToken();
		RefreshToken token = new RefreshToken(
			UUID.randomUUID(),
			accountId,
			UUID.randomUUID(),
			hash(rawToken),
			now,
			now.plus(ttl),
			null,
			null);
		refreshTokens.save(token);
		return new IssuedRefreshToken(rawToken, token);
	}

	/**
	 * Validates and atomically rotates a refresh token.
	 * If a revoked token is presented again (replay or concurrent race), the entire token family is revoked.
	 */
	public RotatedRefreshToken rotate(String rawRefreshToken) {
		if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
			throw new InvalidCredentialsException("Invalid refresh token");
		}
		RefreshToken existing = refreshTokens.findByTokenHash(hash(rawRefreshToken))
			.orElseThrow(() -> new InvalidCredentialsException("Invalid refresh token"));

		Instant now = clock.instant();
		if (existing.isRevoked()) {
			refreshTokens.revokeByFamilyId(existing.familyId(), now);
			throw new InvalidCredentialsException("Refresh token has been revoked");
		}
		if (existing.isExpired(now)) {
			throw new InvalidCredentialsException("Refresh token has expired");
		}

		Account account = accounts.findById(existing.accountId())
			.orElseThrow(() -> {
				refreshTokens.revokeByFamilyId(existing.familyId(), now);
				return new InvalidCredentialsException("Account no longer exists");
			});

		String newRawToken = generateRawToken();
		RefreshToken replacement = new RefreshToken(
			UUID.randomUUID(),
			existing.accountId(),
			existing.familyId(),
			hash(newRawToken),
			now,
			now.plus(ttl),
			null,
			null);

		if (!refreshTokens.rotate(existing.id(), replacement, now)) {
			refreshTokens.revokeByFamilyId(existing.familyId(), now);
			throw new InvalidCredentialsException("Refresh token has been revoked");
		}
		return new RotatedRefreshToken(account, newRawToken, replacement);
	}

	/** Revokes the token family associated with an access token when it belongs to {@code accountId}. */
	public void revokeFamilyForAccount(UUID accountId, UUID familyId) {
		if (accountId == null || familyId == null) {
			return;
		}
		refreshTokens.revokeByAccountIdAndFamilyId(accountId, familyId, clock.instant());
	}

	/**
	 * Revokes the refresh token family when it belongs to {@code accountId}.
	 * Ignores missing/unknown tokens or tokens belonging to another account.
	 */
	public void revokeForAccount(UUID accountId, String rawRefreshToken) {
		if (accountId == null || rawRefreshToken == null || rawRefreshToken.isBlank()) {
			return;
		}
		Optional<RefreshToken> found = refreshTokens.findByTokenHash(hash(rawRefreshToken));
		if (found.isPresent() && accountId.equals(found.get().accountId())) {
			refreshTokens.revokeByFamilyId(found.get().familyId(), clock.instant());
		}
	}

	public int revokeAllForAccount(UUID accountId) {
		Objects.requireNonNull(accountId, "accountId must not be null");
		return refreshTokens.revokeByAccountId(accountId, clock.instant());
	}

	static String hash(String rawToken) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private String generateRawToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public record IssuedRefreshToken(String rawRefreshToken, RefreshToken refreshToken) {
	}

	public record RotatedRefreshToken(Account account, String rawRefreshToken, RefreshToken refreshToken) {
	}
}
