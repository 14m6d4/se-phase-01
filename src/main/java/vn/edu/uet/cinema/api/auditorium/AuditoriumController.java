package vn.edu.uet.cinema.api.auditorium;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.auditorium.Seat;
import vn.edu.uet.cinema.config.OpenApiConfig;

@RestController
class AuditoriumController {

	private final AuditoriumService auditoriums;

	AuditoriumController(AuditoriumService auditoriums) {
		this.auditoriums = auditoriums;
	}

	@PostMapping("/api/cinemas/{id}/auditoriums")
	@ResponseStatus(HttpStatus.CREATED)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	AuditoriumResponse create(@PathVariable UUID id, @Valid @RequestBody AuditoriumRequest request) {
		return AuditoriumResponse.detail(auditoriums.create(id, request.name(), request.rows(), request.seatsPerRow(),
			request.vipRows(), request.coupleRows()));
	}

	@GetMapping("/api/cinemas/{id}/auditoriums")
	List<AuditoriumResponse> list(@PathVariable UUID id) {
		return auditoriums.list(id).stream().map(AuditoriumResponse::summary).toList();
	}

	@GetMapping("/api/auditoriums/{id}")
	AuditoriumResponse get(@PathVariable UUID id) {
		return AuditoriumResponse.detail(auditoriums.get(id));
	}

	@DeleteMapping("/api/auditoriums/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	void delete(@PathVariable UUID id) {
		auditoriums.delete(id);
	}

	@Schema(name = "AuditoriumRequest")
	record AuditoriumRequest(@NotBlank(message = "Name is required")
		@Size(max = 255, message = "Name is not allowed") String name,
		@NotNull(message = "Rows are required") @Min(value = 1, message = "Rows must be at least 1")
		@Max(value = 26, message = "Rows must be at most 26") Integer rows,
		@NotNull(message = "Seats per row are required") @Positive(message = "Seats per row must be positive")
		Integer seatsPerRow,
		@NotNull(message = "VIP rows are required") List<String> vipRows,
		@NotNull(message = "Couple rows are required") List<String> coupleRows) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AuditoriumResponse(UUID id, UUID cinemaId, String name, int rows, int seatsPerRow, List<SeatResponse> seats) {

		static AuditoriumResponse summary(Auditorium auditorium) {
			return new AuditoriumResponse(auditorium.id(), auditorium.cinemaId(), auditorium.name(), auditorium.rows(),
				auditorium.seatsPerRow(), null);
		}

		static AuditoriumResponse detail(Auditorium auditorium) {
			return new AuditoriumResponse(auditorium.id(), auditorium.cinemaId(), auditorium.name(), auditorium.rows(),
				auditorium.seatsPerRow(), auditorium.seats().stream().map(SeatResponse::from).toList());
		}
	}

	record SeatResponse(UUID id, String row, int number, String label, String type) {

		static SeatResponse from(Seat seat) {
			return new SeatResponse(seat.id(), seat.row(), seat.number(), seat.label(), seat.type().name());
		}
	}
}
