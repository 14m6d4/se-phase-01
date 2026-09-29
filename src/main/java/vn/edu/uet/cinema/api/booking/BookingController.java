package vn.edu.uet.cinema.api.booking;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uet.cinema.business.booking.BookedSeat;
import vn.edu.uet.cinema.business.booking.Booking;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.booking.BookingStatus;
import vn.edu.uet.cinema.config.OpenApiConfig;

@RestController
class BookingController {

	private final BookingService bookings;

	BookingController(BookingService bookings) {
		this.bookings = bookings;
	}

	@PostMapping("/api/bookings")
	@ResponseStatus(HttpStatus.CREATED)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	BookingResponse book(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BookingRequest request) {
		Instant now = Instant.now();
		return BookingResponse.from(bookings.book(customerId(jwt), request.showtimeId(), request.seatIds(), now), now);
	}

	@GetMapping("/api/bookings")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	List<BookingResponse> list(@AuthenticationPrincipal Jwt jwt) {
		Instant now = Instant.now();
		return bookings.list(customerId(jwt)).stream().map(booking -> BookingResponse.from(booking, now)).toList();
	}

	@GetMapping("/api/bookings/{id}")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	BookingResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return BookingResponse.from(bookings.get(customerId(jwt), id), Instant.now());
	}

	@PostMapping("/api/bookings/{id}/payment")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	BookingResponse pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		Instant now = Instant.now();
		return BookingResponse.from(bookings.pay(customerId(jwt), id, now), now);
	}

	@DeleteMapping("/api/bookings/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	void abandon(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		bookings.abandon(customerId(jwt), id);
	}

	private static UUID customerId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	record BookingRequest(@NotNull UUID showtimeId, @NotNull List<@NotNull UUID> seatIds) {
	}

	record BookingResponse(UUID id, UUID showtimeId, BookingStatus status, Instant createdAt, Instant expiresAt,
		List<BookedSeat> seats, long totalPrice) {

		static BookingResponse from(Booking booking, Instant now) {
			return new BookingResponse(booking.id(), booking.showtimeId(), booking.statusAt(now), booking.createdAt(),
				booking.expiresAt(), booking.seats(), booking.totalPrice());
		}
	}
}
