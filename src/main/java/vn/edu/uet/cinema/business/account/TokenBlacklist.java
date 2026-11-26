package vn.edu.uet.cinema.business.account;

import java.time.Instant;

/**
 * Port for revoking short-lived access tokens before their natural expiration (S2).
 * Plain Java interface without framework annotations (ADR 0001).
 */
public interface TokenBlacklist {

	void revoke(String tokenId, Instant expiresAt);

	boolean isRevoked(String tokenId, Instant now);
}
