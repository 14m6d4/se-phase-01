package vn.edu.uet.cinema.api.showtime;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.auditorium.Seat;
import vn.edu.uet.cinema.business.showtime.Cancellation;
import vn.edu.uet.cinema.business.showtime.CancellationReason;
import vn.edu.uet.cinema.business.showtime.SeatOccupancyReader;
import vn.edu.uet.cinema.business.showtime.SeatPricing;
import vn.edu.uet.cinema.business.showtime.SeatStatus;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.business.showtime.ShowtimeStatus;
import vn.edu.uet.cinema.config.OpenApiConfig;

@RestController
class ShowtimeController {

	private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

	private final ShowtimeService showtimes;
	private final AuditoriumService auditoriums;
	private final SeatOccupancyReader occupancy;

	ShowtimeController(ShowtimeService showtimes, AuditoriumService auditoriums, SeatOccupancyReader occupancy) {
		this.showtimes = showtimes;
		this.auditoriums = auditoriums;
		this.occupancy = occupancy;
	}

	@PostMapping("/api/showtimes")
	@ResponseStatus(HttpStatus.CREATED)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	ShowtimeResponse create(@Valid @RequestBody ShowtimeRequest request) {
		return ShowtimeResponse.from(showtimes.create(request.movieId(), request.auditoriumId(),
			request.startsAt().toInstant(), request.basePrice()));
	}

	@GetMapping("/api/showtimes")
	List<ShowtimeResponse> list(@RequestParam LocalDate date, @RequestParam(required = false) UUID cinemaId,
		@RequestParam(required = false) UUID movieId) {
		Instant from = date.atStartOfDay(VIETNAM_ZONE).toInstant();
		Instant to = date.plusDays(1).atStartOfDay(VIETNAM_ZONE).toInstant();
		return showtimes.list(from, to, cinemaId, movieId).stream().map(ShowtimeResponse::from).toList();
	}

	@GetMapping("/api/showtimes/{id}")
	ShowtimeResponse get(@PathVariable UUID id) {
		return ShowtimeResponse.from(showtimes.get(id));
	}

	@GetMapping("/api/showtimes/{id}/seats")
	List<SeatMapResponse> seats(@PathVariable UUID id) {
		Showtime showtime = showtimes.getScheduled(id);
		Map<UUID, SeatStatus> statuses = occupancy.statuses(id, Instant.now());
		return auditoriums.get(showtime.auditoriumId()).seats().stream()
			.map(seat -> SeatMapResponse.from(seat, SeatPricing.price(seat.type(), showtime.basePrice()),
				statuses.getOrDefault(seat.id(), SeatStatus.AVAILABLE)))
			.toList();
	}

	@PostMapping("/api/showtimes/{id}/cancellation")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	ShowtimeResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancellationRequest request) {
		return ShowtimeResponse.from(showtimes.cancel(id, request.reason(), request.note(), Instant.now()));
	}

	@DeleteMapping("/api/showtimes/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	void delete(@PathVariable UUID id) {
		showtimes.delete(id);
	}

	record ShowtimeRequest(@NotNull(message = "Movie is required") UUID movieId,
		@NotNull(message = "Auditorium is required") UUID auditoriumId,
		@NotNull(message = "Start time is required") OffsetDateTime startsAt,
		@NotNull(message = "Base price is required") @Positive(message = "Base price must be positive") Long basePrice) {
	}

	record CancellationRequest(@NotNull(message = "Cancellation reason is required") CancellationReason reason,
		@Schema(description = "Optional note, up to 200 characters.", maxLength = 200) String note) {
	}

	/** {@code cancellation} is null unless the Showtime is Cancelled. */
	record ShowtimeResponse(UUID id, UUID movieId, UUID auditoriumId, Instant startsAt, Instant endsAt, long basePrice,
		ShowtimeStatus status, CancellationResponse cancellation) {

		static ShowtimeResponse from(Showtime showtime) {
			Cancellation cancellation = showtime.cancellation();
			return new ShowtimeResponse(showtime.id(), showtime.movieId(), showtime.auditoriumId(), showtime.startsAt(),
				showtime.endsAt(), showtime.basePrice(), showtime.status(),
				cancellation == null ? null : new CancellationResponse(cancellation.reason(), cancellation.note()));
		}
	}

	record CancellationResponse(CancellationReason reason, String note) {
	}

	record SeatMapResponse(UUID id, String row, int number, String label, String type, long price, SeatStatus status) {

		static SeatMapResponse from(Seat seat, long price, SeatStatus status) {
			return new SeatMapResponse(seat.id(), seat.row(), seat.number(), seat.label(), seat.type().name(), price, status);
		}
	}
}
