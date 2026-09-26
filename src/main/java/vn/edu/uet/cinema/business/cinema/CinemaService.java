package vn.edu.uet.cinema.business.cinema;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.NotFoundException;

public class CinemaService {

	private final CinemaRepository cinemas;

	public CinemaService(CinemaRepository cinemas) {
		this.cinemas = cinemas;
	}

	public Cinema create(String name, String address) {
		return cinemas.save(new Cinema(UUID.randomUUID(), name, address));
	}

	public List<Cinema> list() {
		return cinemas.findAll();
	}

	public Cinema get(UUID id) {
		return cinemas.findById(id).orElseThrow(() -> new NotFoundException("Cinema not found"));
	}

	public Cinema update(UUID id, String name, String address) {
		get(id);
		return cinemas.save(new Cinema(id, name, address));
	}

	public void delete(UUID id) {
		get(id);
		cinemas.deleteById(id);
	}
}
