package vn.edu.uet.cinema.business.movie;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;

/** In-memory {@link MovieRepository} for business tests. */
public class InMemoryMovies implements MovieRepository {

	private final Map<UUID, Movie> rows = new LinkedHashMap<>();

	@Override
	public List<Movie> findAll() {
		return new ArrayList<>(rows.values());
	}

	@Override
	public PageSlice<Movie> findAll(PageQuery query) {
		List<Movie> sorted = rows.values().stream()
			.sorted(Comparator.comparing(Movie::title).thenComparing(Movie::id))
			.toList();
		int from = Math.min(query.offset(), sorted.size());
		int to = Math.min(from + query.size(), sorted.size());
		return new PageSlice<>(sorted.subList(from, to), query.page(), query.size(), sorted.size());
	}

	@Override
	public Optional<Movie> findById(UUID id) {
		return Optional.ofNullable(rows.get(id));
	}

	@Override
	public Movie save(Movie movie) {
		rows.put(movie.id(), movie);
		return movie;
	}

	@Override
	public void deleteById(UUID id) {
		rows.remove(id);
	}
}
