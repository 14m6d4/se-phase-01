package vn.edu.uet.cinema.business.movie;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.edu.uet.cinema.business.NotFoundException;

public class MovieService {

	private final MovieRepository movies;

	public MovieService(MovieRepository movies) {
		this.movies = movies;
	}

	public Movie create(String title, String description, int durationMinutes, String genre, AgeRating ageRating,
		LocalDate releaseDate, String posterUrl) {
		return movies.save(new Movie(UUID.randomUUID(), title, description, durationMinutes, genre, ageRating,
			releaseDate, posterUrl));
	}

	public List<Movie> list() {
		return movies.findAll();
	}

	public Movie get(UUID id) {
		return movies.findById(id).orElseThrow(() -> new NotFoundException("Movie not found"));
	}

	public Movie update(UUID id, String title, String description, int durationMinutes, String genre,
		AgeRating ageRating, LocalDate releaseDate, String posterUrl) {
		get(id);
		return movies.save(new Movie(id, title, description, durationMinutes, genre, ageRating, releaseDate, posterUrl));
	}

	public void delete(UUID id) {
		get(id);
		movies.deleteById(id);
	}
}
