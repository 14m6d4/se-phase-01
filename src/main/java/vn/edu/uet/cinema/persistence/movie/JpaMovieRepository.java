package vn.edu.uet.cinema.persistence.movie;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import vn.edu.uet.cinema.business.movie.Movie;
import vn.edu.uet.cinema.business.movie.MovieRepository;

@Repository
class JpaMovieRepository implements MovieRepository {

	private final MovieEntityRepository jpa;

	JpaMovieRepository(MovieEntityRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	public List<Movie> findAll() {
		return jpa.findAll().stream().map(MovieEntity::toMovie).toList();
	}

	@Override
	public Optional<Movie> findById(UUID id) {
		return jpa.findById(id).map(MovieEntity::toMovie);
	}

	@Override
	public Movie save(Movie movie) {
		return jpa.save(MovieEntity.from(movie)).toMovie();
	}

	@Override
	public void deleteById(UUID id) {
		jpa.deleteById(id);
	}
}
