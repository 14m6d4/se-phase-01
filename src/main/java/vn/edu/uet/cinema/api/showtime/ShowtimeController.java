package vn.edu.uet.cinema.api.showtime;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.config.OpenApiConfig;

@RestController
class ShowtimeController {

	private final ShowtimeService showtimes;

	ShowtimeController(ShowtimeService showtimes) {
		this.showtimes = showtimes;
	}

	@PostMapping("/api/showtimes")
	@ResponseStatus(HttpStatus.CREATED)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	ShowtimeResponse create(@Valid @RequestBody ShowtimeRequest request) {
		return ShowtimeResponse.from(showtimes.create(request.movieId(), request.auditoriumId(),
			request.startsAt().toInstant(), request.basePrice()));
	}

	@DeleteMapping("/api/showtimes/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	void delete(@PathVariable UUID id) {
		showtimes.delete(id);
	}

	record ShowtimeRequest(@NotNull UUID movieId, @NotNull UUID auditoriumId, @NotNull OffsetDateTime startsAt,
		@NotNull @Positive Long basePrice) {
	}

	record ShowtimeResponse(UUID id, UUID movieId, UUID auditoriumId, Instant startsAt, Instant endsAt, long basePrice) {

		static ShowtimeResponse from(Showtime showtime) {
			return new ShowtimeResponse(showtime.id(), showtime.movieId(), showtime.auditoriumId(), showtime.startsAt(),
				showtime.endsAt(), showtime.basePrice());
		}
	}
}
