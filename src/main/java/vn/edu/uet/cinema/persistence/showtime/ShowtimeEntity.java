package vn.edu.uet.cinema.persistence.showtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import vn.edu.uet.cinema.business.showtime.Showtime;

@Entity
@Table(name = "showtime")
class ShowtimeEntity {

	@Id
	private UUID id;
	@Column(name = "movie_id")
	private UUID movieId;
	@Column(name = "auditorium_id")
	private UUID auditoriumId;
	@Column(name = "starts_at")
	private Instant startsAt;
	@Column(name = "ends_at")
	private Instant endsAt;
	@Column(name = "base_price")
	private long basePrice;

	protected ShowtimeEntity() {
	}

	static ShowtimeEntity from(Showtime showtime) {
		ShowtimeEntity entity = new ShowtimeEntity();
		entity.id = showtime.id();
		entity.movieId = showtime.movieId();
		entity.auditoriumId = showtime.auditoriumId();
		entity.startsAt = showtime.startsAt();
		entity.endsAt = showtime.endsAt();
		entity.basePrice = showtime.basePrice();
		return entity;
	}

	Showtime toShowtime() {
		return new Showtime(id, movieId, auditoriumId, startsAt, endsAt, basePrice);
	}
}
