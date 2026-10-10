package vn.edu.uet.cinema.api.account;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
import vn.edu.uet.cinema.config.OpenApiConfig;

@RestController
class AccountController {

	private final AccountService accounts;
	private final TokenIssuer tokens;

	AccountController(AccountService accounts, TokenIssuer tokens) {
		this.accounts = accounts;
		this.tokens = tokens;
	}

	@PostMapping("/api/auth/register")
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
	TokenResponse login(@Valid @RequestBody LoginRequest request) {
		Account account = accounts.authenticate(request.email(), request.password());
		return new TokenResponse(tokens.issue(account), "Bearer", TokenIssuer.TTL.toSeconds());
	}

	@GetMapping("/api/me")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	AccountResponse me(@AuthenticationPrincipal Jwt jwt) {
		return AccountResponse.from(accounts.get(UUID.fromString(jwt.getSubject())));
	}

	// BCrypt only reads the first 72 bytes of a password.
	@Schema(name = "RegisterRequest")
	record RegisterRequest(@NotBlank(message = "Email is required") @Email(message = "Email must be valid")
		@Size(max = 255, message = "Email is not allowed") String email,
		@NotBlank(message = "Password is required")
		@Size(min = 8, message = "Password must be at least 8 characters") @RegistrationPasswordLimit String password,
		@NotBlank(message = "Full name is required") @Size(max = 255, message = "Full name is not allowed")
		String fullName) {
	}

	record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	record AccountResponse(UUID id, String email, String fullName, String role) {

		static AccountResponse from(Account account) {
			return new AccountResponse(account.id(), account.email(), account.fullName(), account.role().name());
		}
	}
}
