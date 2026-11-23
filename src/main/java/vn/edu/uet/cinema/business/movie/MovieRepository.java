package vn.edu.uet.cinema.business.movie;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;

public interface MovieRepository {

	List<Movie> findAll();

	PageSlice<Movie> findAll(PageQuery query);

	Optional<Movie> findById(UUID id);

	Movie save(Movie movie);

	void deleteById(UUID id);
}
