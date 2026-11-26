package vn.edu.uet.cinema.config;

import static org.springframework.http.HttpMethod.GET;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import vn.edu.uet.cinema.business.account.InMemoryTokenBlacklist;
import vn.edu.uet.cinema.business.account.PasswordHasher;
import vn.edu.uet.cinema.business.account.TokenBlacklist;

/**
 * The only place that decides who may call what. Controllers never check authentication themselves:
 * Spring Security's filter chain validates the bearer JWT and applies these rules before any controller runs.
 */
@Configuration
public class SecurityConfig {

	/** Token claim holding the Account's roles, mapped to {@code ROLE_*} authorities. */
	public static final String ROLES_CLAIM = "roles";

	/** Token claim linking an access JWT to its refresh token family so logout without a body revokes the session. */
	public static final String FAMILY_ID_CLAIM = "familyId";

	private static final String[] CATALOGUE = {"/api/cinemas/**", "/api/auditoriums/**", "/api/movies/**",
		"/api/showtimes/**"};

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitingFilter rateLimitingFilter) throws Exception {
		AuthenticationEntryPoint unauthorized = problemJson(new BearerTokenAuthenticationEntryPoint());
		AccessDeniedHandler forbidden = problemJson(new BearerTokenAccessDeniedHandler());
		return http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/error",
					"/actuator/health", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/v3/api-docs.yaml")
				.permitAll()
				.requestMatchers(GET, CATALOGUE).permitAll()
				.requestMatchers(CATALOGUE).hasRole("ADMIN")
				.requestMatchers("/api/bookings/**").hasRole("CUSTOMER")
				.requestMatchers("/actuator/metrics/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			.exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
			.oauth2ResourceServer(oauth -> oauth
				.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesClaimConverter()))
				.authenticationEntryPoint(unauthorized)
				.accessDeniedHandler(forbidden))
			.addFilterAfter(rateLimitingFilter, BearerTokenAuthenticationFilter.class)
			.build();
	}

	@Bean
	RateLimitingFilter rateLimitingFilter(
		@Value("${app.rate-limit.enabled:true}") boolean enabled,
		@Value("${app.rate-limit.auth.capacity:200}") long authCapacity,
		@Value("${app.rate-limit.auth.refill-tokens:200}") long authRefillTokens,
		@Value("${app.rate-limit.auth.refill-duration-seconds:60}") long authDurationSeconds,
		@Value("${app.rate-limit.api.capacity:3000}") long apiCapacity,
		@Value("${app.rate-limit.api.refill-tokens:3000}") long apiRefillTokens,
		@Value("${app.rate-limit.api.refill-duration-seconds:60}") long apiDurationSeconds) {
		return new RateLimitingFilter(enabled, authCapacity, authRefillTokens, authDurationSeconds,
			apiCapacity, apiRefillTokens, apiDurationSeconds);
	}

	@Bean
	FilterRegistrationBean<RateLimitingFilter> rateLimitingFilterRegistration(RateLimitingFilter filter) {
		FilterRegistrationBean<RateLimitingFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	SecretKey jwtKey(@Value("${app.jwt.secret}") String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException("JWT_SECRET must be at least 32 bytes for HS256");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
	}

	@Bean
	TokenBlacklist tokenBlacklist() {
		return new InMemoryTokenBlacklist();
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtKey, TokenBlacklist tokenBlacklist) {
		return jwtDecoder(jwtKey, tokenBlacklist, Clock.systemUTC());
	}

	JwtDecoder jwtDecoder(SecretKey jwtKey, TokenBlacklist tokenBlacklist, Clock clock) {
		NimbusJwtDecoder delegate = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
		// Disable Nimbus's default 60s clock skew so a logged-out JWT cannot be resurrected during [exp, exp + 60s].
		JwtTimestampValidator timestampValidator = new JwtTimestampValidator(Duration.ZERO);
		timestampValidator.setClock(clock);
		delegate.setJwtValidator(timestampValidator);
		ConcurrentHashMap<String, Jwt> cache = new ConcurrentHashMap<>(2048);
		return token -> {
			Jwt cached = cache.get(token);
			Instant now = clock.instant();
			if (cached != null) {
				if (tokenBlacklist.isRevoked(tokenKey(cached), now)) {
					cache.remove(token, cached);
					throw new BadJwtException("Token has been revoked");
				}
				Instant expiresAt = cached.getExpiresAt();
				if (expiresAt == null || expiresAt.isAfter(now)) {
					return cached;
				}
				cache.remove(token, cached);
			}
			Jwt decoded = delegate.decode(token);
			if (tokenBlacklist.isRevoked(tokenKey(decoded), now)) {
				throw new BadJwtException("Token has been revoked");
			}
			Instant expiresAt = decoded.getExpiresAt();
			if (expiresAt == null || expiresAt.isAfter(now)) {
				if (cache.size() >= 4096) {
					cache.clear();
				}
				cache.putIfAbsent(token, decoded);
			}
			return decoded;
		};
	}

	public static String tokenKey(Jwt jwt) {
		String id = jwt.getId();
		return id != null && !id.isBlank() ? id : jwt.getTokenValue();
	}

	@Bean
	PasswordHasher passwordHasher() {
		BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
		return new PasswordHasher() {
			@Override
			public String hash(String rawPassword) {
				return bcrypt.encode(rawPassword);
			}

			@Override
			public boolean matches(String rawPassword, String hash) {
				return bcrypt.matches(rawPassword, hash);
			}
		};
	}

	/** Lets the bearer defaults set the status and WWW-Authenticate header, then adds an RFC 9457 body. */
	private static AuthenticationEntryPoint problemJson(AuthenticationEntryPoint delegate) {
		return (request, response, e) -> {
			delegate.commence(request, response, e);
			writeProblem(response);
		};
	}

	private static AccessDeniedHandler problemJson(AccessDeniedHandler delegate) {
		return (request, response, e) -> {
			delegate.handle(request, response, e);
			writeProblem(response);
		};
	}

	private static void writeProblem(HttpServletResponse response) throws IOException {
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d}"
			.formatted(status.getReasonPhrase(), status.value()));
	}

	/** Maps the token's roles claim to {@code ROLE_*} authorities used by {@code hasRole}. */
	private static JwtAuthenticationConverter rolesClaimConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName(ROLES_CLAIM);
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}
}
