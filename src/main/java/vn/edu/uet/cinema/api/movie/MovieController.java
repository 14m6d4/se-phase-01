package vn.edu.uet.cinema.api.movie;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.Movie;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.config.OpenApiConfig;

@RestController
class MovieController {

	private final MovieService movies;

	MovieController(MovieService movies) {
		this.movies = movies;
	}

	@PostMapping("/api/movies")
	@ResponseStatus(HttpStatus.CREATED)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	MovieResponse create(@Valid @RequestBody MovieRequest request) {
		return MovieResponse.from(movies.create(request.title(), request.description(), request.durationMinutes(),
			request.genre(), request.ageRating(), request.releaseDate(), request.posterUrl()));
	}

	@GetMapping("/api/movies")
	List<MovieResponse> list() {
		return movies.list().stream().map(MovieResponse::from).toList();
	}

	@GetMapping("/api/movies/{id}")
	MovieResponse get(@PathVariable UUID id) {
		return MovieResponse.from(movies.get(id));
	}

	@PutMapping("/api/movies/{id}")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	MovieResponse update(@PathVariable UUID id, @Valid @RequestBody MovieRequest request) {
		return MovieResponse.from(movies.update(id, request.title(), request.description(), request.durationMinutes(),
			request.genre(), request.ageRating(), request.releaseDate(), request.posterUrl()));
	}

	@DeleteMapping("/api/movies/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	void delete(@PathVariable UUID id) {
		movies.delete(id);
	}

	record MovieRequest(@NotBlank @Size(max = 255) String title, @NotBlank String description,
		@NotNull @Positive Integer durationMinutes, @NotBlank @Size(max = 255) String genre,
		@NotNull AgeRating ageRating, @NotNull LocalDate releaseDate, @NotBlank String posterUrl) {
	}

	record MovieResponse(UUID id, String title, String description, int durationMinutes, String genre,
		AgeRating ageRating, LocalDate releaseDate, String posterUrl) {

		static MovieResponse from(Movie movie) {
			return new MovieResponse(movie.id(), movie.title(), movie.description(), movie.durationMinutes(), movie.genre(),
				movie.ageRating(), movie.releaseDate(), movie.posterUrl());
		}
	}
}
