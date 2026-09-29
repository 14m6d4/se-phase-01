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
		return BookingResponse.from(bookings.book(UUID.fromString(jwt.getSubject()), request.showtimeId(),
			request.seatIds(), Instant.now()));
	}

	record BookingRequest(@NotNull UUID showtimeId, @NotNull List<@NotNull UUID> seatIds) {
	}

	record BookingResponse(UUID id, UUID showtimeId, BookingStatus status, Instant createdAt, Instant expiresAt,
		List<BookedSeat> seats, long totalPrice) {

		static BookingResponse from(Booking booking) {
			return new BookingResponse(booking.id(), booking.showtimeId(), booking.status(), booking.createdAt(),
				booking.expiresAt(), booking.seats(), booking.totalPrice());
		}
	}
}
