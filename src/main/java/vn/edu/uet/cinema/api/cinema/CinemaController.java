package vn.edu.uet.cinema.api.cinema;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uet.cinema.business.cinema.Cinema;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.config.OpenApiConfig;

@RestController
class CinemaController {

	private final CinemaService cinemas;

	CinemaController(CinemaService cinemas) {
		this.cinemas = cinemas;
	}

	@PostMapping("/api/cinemas")
	@ResponseStatus(HttpStatus.CREATED)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	CinemaResponse create(@Valid @RequestBody CinemaRequest request) {
		return CinemaResponse.from(cinemas.create(request.name(), request.address()));
	}

	@GetMapping("/api/cinemas")
	List<CinemaResponse> list() {
		return cinemas.list().stream().map(CinemaResponse::from).toList();
	}

	@GetMapping("/api/cinemas/{id}")
	CinemaResponse get(@PathVariable UUID id) {
		return CinemaResponse.from(cinemas.get(id));
	}

	@PutMapping("/api/cinemas/{id}")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	CinemaResponse update(@PathVariable UUID id, @Valid @RequestBody CinemaRequest request) {
		return CinemaResponse.from(cinemas.update(id, request.name(), request.address()));
	}

	@DeleteMapping("/api/cinemas/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	void delete(@PathVariable UUID id) {
		cinemas.delete(id);
	}

	@Schema(name = "CinemaRequest")
	record CinemaRequest(@NotBlank(message = "Name is required")
		@Size(max = 255, message = "Name is not allowed") String name,
		@NotBlank(message = "Address is required") String address) {
	}

	record CinemaResponse(UUID id, String name, String address) {

		static CinemaResponse from(Cinema cinema) {
			return new CinemaResponse(cinema.id(), cinema.name(), cinema.address());
		}
	}
}
