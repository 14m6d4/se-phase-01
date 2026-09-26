package vn.edu.uet.cinema.business.auditorium;

import java.util.List;
import java.util.UUID;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.cinema.CinemaService;

public class AuditoriumService {

	private final CinemaService cinemas;
	private final AuditoriumRepository auditoriums;

	public AuditoriumService(CinemaService cinemas, AuditoriumRepository auditoriums) {
		this.cinemas = cinemas;
		this.auditoriums = auditoriums;
	}

	public Auditorium create(UUID cinemaId, String name, int rows, int seatsPerRow, List<String> vipRows,
		List<String> coupleRows) {
		cinemas.get(cinemaId);
		if (auditoriums.existsByCinemaIdAndName(cinemaId, name)) {
			throw new AuditoriumNameTakenException();
		}
		return auditoriums.save(new Auditorium(UUID.randomUUID(), cinemaId, name, rows, seatsPerRow,
			SeatLayout.generate(rows, seatsPerRow, vipRows, coupleRows)));
	}

	public List<Auditorium> list(UUID cinemaId) {
		cinemas.get(cinemaId);
		return auditoriums.findByCinemaId(cinemaId);
	}

	public Auditorium get(UUID id) {
		return auditoriums.findById(id).orElseThrow(() -> new NotFoundException("Auditorium not found"));
	}

	public void delete(UUID id) {
		get(id);
		auditoriums.deleteById(id);
	}
}
