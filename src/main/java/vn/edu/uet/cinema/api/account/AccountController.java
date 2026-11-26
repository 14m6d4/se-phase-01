package vn.edu.uet.cinema.api.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uet.cinema.business.account.Account;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.account.RefreshTokenService;
import vn.edu.uet.cinema.business.account.TokenBlacklist;
import vn.edu.uet.cinema.config.OpenApiConfig;
import vn.edu.uet.cinema.config.SecurityConfig;

@RestController
@Tag(name = "Accounts", description = "Customer registration, login, token refresh, logout, and the authenticated account.")
class AccountController {

	private final AccountService accounts;
	private final TokenIssuer tokens;
	private final RefreshTokenService refreshTokens;
	private final TokenBlacklist tokenBlacklist;

	AccountController(AccountService accounts, TokenIssuer tokens, RefreshTokenService refreshTokens,
		TokenBlacklist tokenBlacklist) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.refreshTokens = refreshTokens;
		this.tokenBlacklist = tokenBlacklist;
	}

	@PostMapping("/api/auth/register")
	@Operation(summary = "Register a customer account", description = "Public self-registration creates a CUSTOMER account. "
		+ "Email addresses are normalized; an existing email returns 409. The password must contain at least 8 characters.")
	@ResponseStatus(HttpStatus.CREATED)
	@ApiResponse(responseCode = "400", description = "The request contains invalid fields.",
		content = @Content(mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ValidationProblem"),
			examples = @ExampleObject(name = "Invalid registration fields", value = "{\"type\":\"about:blank\","
				+ "\"title\":\"Bad Request\",\"status\":400,\"detail\":\"Request validation failed.\","
				+ "\"errors\":[{\"field\":\"password\","
				+ "\"message\":\"Password must be at least 8 characters\"}]}")))
	AccountResponse register(@Valid @RequestBody RegisterRequest request) {
		return AccountResponse.from(accounts.registerCustomer(request.email(), request.password(), request.fullName()));
	}

	@PostMapping("/api/auth/login")
	@Operation(summary = "Log in", description = "Public login authenticates with email and password and returns a short-lived "
		+ "Bearer access token (default 15 minutes) and a revocable rotating refresh token. Unknown email and wrong password "
		+ "share the same 401 response; login applies only nonblank validation.")
	TokenResponse login(@Valid @RequestBody LoginRequest request) {
		Account account = accounts.authenticate(request.email(), request.password());
		RefreshTokenService.IssuedRefreshToken issued = refreshTokens.issue(account.id());
		return new TokenResponse(tokens.issue(account, issued.refreshToken().familyId()), issued.rawRefreshToken(),
			"Bearer", tokens.ttl().toSeconds());
	}

	@PostMapping("/api/auth/refresh")
	@Operation(summary = "Refresh access token", description = "Public endpoint that exchanges an active refresh token "
		+ "for a new short-lived Bearer access token and a rotated refresh token. Presenting an unknown, expired, or "
		+ "revoked refresh token returns 401; replaying an already-used refresh token also revokes its entire token family.")
	TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		RefreshTokenService.RotatedRefreshToken rotated = refreshTokens.rotate(request.refreshToken());
		return new TokenResponse(tokens.issue(rotated.account(), rotated.refreshToken().familyId()),
			rotated.rawRefreshToken(), "Bearer", tokens.ttl().toSeconds());
	}

	@PostMapping("/api/auth/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Log out", description = "Revokes the caller's current Bearer access token by adding it to the "
		+ "token blacklist so further requests using that token fail with 401, and revokes the refresh token family "
		+ "linked to the access token. If an additional refreshToken belonging to the caller is supplied in the optional "
		+ "request body, its refresh token family is also revoked.")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	void logout(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) @Valid LogoutRequest request) {
		tokenBlacklist.revoke(SecurityConfig.tokenKey(jwt), jwt.getExpiresAt());
		UUID accountId = UUID.fromString(jwt.getSubject());
		String familyClaim = jwt.getClaimAsString(SecurityConfig.FAMILY_ID_CLAIM);
		if (familyClaim != null && !familyClaim.isBlank()) {
			try {
				refreshTokens.revokeFamilyForAccount(accountId, UUID.fromString(familyClaim));
			} catch (IllegalArgumentException ignored) {
				// Ignore malformed familyId claim rather than failing logout.
			}
		}
		if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
			refreshTokens.revokeForAccount(accountId, request.refreshToken());
		}
	}

	@GetMapping("/api/me")
	@Operation(summary = "Get the authenticated account", description = "Returns the account identified by the JWT subject. "
		+ "Any authenticated role may call this operation; if the account no longer exists the response is 404.")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	AccountResponse me(@AuthenticationPrincipal Jwt jwt) {
		return AccountResponse.from(accounts.get(UUID.fromString(jwt.getSubject())));
	}

	// BCrypt only reads the first 72 bytes of a password.
	@Schema(name = "RegisterRequest")
	record RegisterRequest(@NotBlank(message = "Email is required") @Email(message = "Email must be valid")
		@Size(max = 255, message = "Email is not allowed")
		@Schema(description = "Nonblank email address used to identify the account.", minLength = 1,
			example = "alice@example.com") String email,
		@NotBlank(message = "Password is required")
		@Size(min = 8, message = "Password must be at least 8 characters") @RegistrationPasswordLimit
		@Schema(description = "Password with a minimum length of 8 characters.", minLength = 8,
			example = "correctHorseBatteryStaple") String password,
		@NotBlank(message = "Full name is required") @Size(max = 255, message = "Full name is not allowed")
		@Schema(description = "Customer's nonblank full name.", minLength = 1, example = "Alice Nguyen") String fullName) {
	}

	record LoginRequest(@NotBlank @Schema(description = "Registered email address.", minLength = 1,
		example = "alice@example.com") String email,
		@NotBlank @Schema(description = "Account password.", minLength = 1,
			example = "correctHorseBatteryStaple") String password) {
	}

	record RefreshRequest(@NotBlank(message = "Refresh token is required")
		@Schema(description = "Active refresh token returned by login or a previous refresh.", minLength = 1,
			example = "dGhpcy1pcy1hLXNhbXBsZS1yZWZyZXNoLXRva2Vu") String refreshToken) {
	}

	record LogoutRequest(
		@Schema(description = "Optional refresh token to revoke alongside the current access token.",
			example = "dGhpcy1pcy1hLXNhbXBsZS1yZWZyZXNoLXRva2Vu") String refreshToken) {
	}

	record TokenResponse(@Schema(description = "Signed HS256 access token.") String accessToken,
		@Schema(description = "Opaque revocable rotating refresh token.") String refreshToken,
		@Schema(description = "Authorization scheme.", example = "Bearer") String tokenType,
		@Schema(description = "Access token lifetime in seconds.", example = "900") long expiresIn) {
	}

	record AccountResponse(@Schema(description = "Account identifier.", example = "550e8400-e29b-41d4-a716-446655440000")
		UUID id,
		@Schema(description = "Normalized email address.", example = "alice@example.com") String email,
		@Schema(description = "Account holder's full name.", example = "Alice Nguyen") String fullName,
		@Schema(description = "Account role.", example = "CUSTOMER") String role) {

		static AccountResponse from(Account account) {
			return new AccountResponse(account.id(), account.email(), account.fullName(), account.role().name());
		}
	}
}
