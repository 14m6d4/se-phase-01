package vn.edu.uet.cinema.config;

import static org.springframework.http.HttpMethod.GET;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import vn.edu.uet.cinema.business.account.PasswordHasher;

/**
 * The only place that decides who may call what. Controllers never check authentication themselves:
 * Spring Security's filter chain validates the bearer JWT and applies these rules before any controller runs.
 */
@Configuration
class SecurityConfig {

	private static final String[] CATALOGUE = {"/api/cinemas/**", "/api/auditoriums/**", "/api/movies/**",
		"/api/showtimes/**"};

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/auth/**", "/error", "/actuator/health", "/swagger-ui.html", "/swagger-ui/**",
					"/v3/api-docs/**").permitAll()
				.requestMatchers(GET, CATALOGUE).permitAll()
				.requestMatchers(CATALOGUE).hasRole("ADMIN")
				.requestMatchers("/api/bookings/**").hasRole("CUSTOMER")
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesClaimConverter())))
			.build();
	}

	@Bean
	SecretKey jwtKey(@Value("${app.jwt.secret}") String secret) {
		return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtKey) {
		return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
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

	/** Maps the token's {@code roles} claim to {@code ROLE_*} authorities used by {@code hasRole}. */
	private static JwtAuthenticationConverter rolesClaimConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("roles");
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}
}
