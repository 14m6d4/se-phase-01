package vn.edu.uet.cinema.business.cinema;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;

public interface CinemaRepository {

	List<Cinema> findAll();

	PageSlice<Cinema> findAll(PageQuery query);

	Optional<Cinema> findById(UUID id);

	Cinema save(Cinema cinema);

	void deleteById(UUID id);
}
