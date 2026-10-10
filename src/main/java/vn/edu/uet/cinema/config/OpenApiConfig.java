package vn.edu.uet.cinema.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
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

	@Bean
	OpenApiCustomizer validationProblemSchema() {
		return openApi -> {
			addValidationProblemSchema(openApi);
			hideStorageLimits(openApi);
		};
	}

	private static void addValidationProblemSchema(OpenAPI openApi) {
		if (openApi.getComponents() == null) {
			openApi.setComponents(new Components());
		}
		ObjectSchema validationError = new ObjectSchema();
		validationError.addProperty("field", new StringSchema());
		validationError.addProperty("message", new StringSchema());
		validationError.setRequired(List.of("field", "message"));

		ArraySchema errors = new ArraySchema();
		errors.setItems(validationError);
		ObjectSchema problem = new ObjectSchema();
		StringSchema type = new StringSchema();
		type.setDefault("about:blank");
		problem.addProperty("type", type);
		problem.addProperty("title", new StringSchema());
		problem.addProperty("status", new IntegerSchema());
		problem.addProperty("detail", new StringSchema());
		problem.addProperty("instance", new StringSchema());
		problem.addProperty("errors", errors);
		problem.setRequired(List.of("title", "status", "detail"));
		openApi.getComponents().addSchemas("ValidationProblem", problem);
	}

	private static void hideStorageLimits(OpenAPI openApi) {
		Map<String, Set<String>> storageLimits = Map.of(
			"RegisterRequest", Set.of("email", "password", "fullName"),
			"CinemaRequest", Set.of("name"),
			"AuditoriumRequest", Set.of("name"),
			"MovieRequest", Set.of("title", "genre"));
		Map<String, Schema> schemas = openApi.getComponents().getSchemas();
		if (schemas == null) {
			return;
		}
		storageLimits.forEach((schemaName, fields) -> {
			Schema<?> schema = schemas.get(schemaName);
			if (schema == null || schema.getProperties() == null) {
				return;
			}
			fields.forEach(field -> {
				Object property = schema.getProperties().get(field);
				if (property instanceof Schema<?> propertySchema) {
					propertySchema.setMaxLength(null);
				}
			});
		});
	}
}
