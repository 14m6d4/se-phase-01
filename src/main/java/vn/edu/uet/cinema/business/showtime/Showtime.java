package vn.edu.uet.cinema.business.showtime;

import java.time.Instant;
import java.util.UUID;

public record Showtime(UUID id, UUID movieId, UUID auditoriumId, Instant startsAt, Instant endsAt, long basePrice) {
}
