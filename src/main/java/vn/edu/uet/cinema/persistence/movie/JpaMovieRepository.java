package vn.edu.uet.cinema.persistence.movie;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import vn.edu.uet.cinema.business.ConflictException;
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
	@Transactional
	public void deleteById(UUID id) {
		try {
			jpa.deleteById(id);
			jpa.flush();
		} catch (DataIntegrityViolationException e) {
			if (hasConstraint(e, "fk_showtime_movie")) {
				throw new ConflictException("Movie has Showtimes");
			}
			throw e;
		}
	}

	private static boolean hasConstraint(Throwable error, String expected) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation && expected.equals(violation.getConstraintName())) {
				return true;
			}
		}
		return false;
	}
}
