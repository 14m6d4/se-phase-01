package vn.edu.uet.cinema.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI at /swagger-ui.html: log in, then paste the accessToken into "Authorize".
 * Protected operations declare {@code @SecurityRequirement(name = BEARER_AUTH)} themselves, so public ones show no lock.
 */
@Configuration
@OpenAPIDefinition(
	info = @Info(title = "Cinema Booking API", version = "v1",
		description = "Cinema chain backend: catalogue management and seat booking."))
@SecurityScheme(name = OpenApiConfig.BEARER_AUTH, type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {

	public static final String BEARER_AUTH = "bearerAuth";
}
