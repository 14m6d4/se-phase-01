package vn.edu.uet.cinema.api.showtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.edu.uet.cinema.business.showtime.SeatOccupancyReader;
import vn.edu.uet.cinema.business.showtime.SeatStatus;

@Component
class EmptySeatOccupancyReader implements SeatOccupancyReader {

	@Override
	public Map<UUID, SeatStatus> statuses(UUID showtimeId, Instant now) {
		// ponytail: show all seats AVAILABLE until issue #4 adds Booking/Hold persistence; replace this adapter then.
		return Map.of();
	}
}
