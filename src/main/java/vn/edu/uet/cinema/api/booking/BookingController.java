package vn.edu.uet.cinema.api.booking;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uet.cinema.api.PaginationHeaders;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.showtime.inventory.BookedSeat;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;
import vn.edu.uet.cinema.config.OpenApiConfig;

@RestController
@Tag(name = "Bookings", description = "Customer-owned seat holds, payment, and booking retrieval.")
class BookingController {

	private final BookingService bookings;

	BookingController(BookingService bookings) {
		this.bookings = bookings;
	}

	@PostMapping("/api/bookings")
	@Operation(summary = "Book seats", description = "Creates a 10-minute Pending hold for 1–8 unique seats in a future, "
		+ "non-cancelled showtime. Customers can have at most one live Pending booking; taken seats, cancelled/started "
		+ "showtimes, or an existing hold cause 409. Duplicate IDs or seats outside its auditorium are 400; an unknown "
		+ "showtime is 404. Requires CUSTOMER.")
	@ResponseStatus(HttpStatus.CREATED)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	BookingResponse book(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BookingRequest request,
		HttpServletRequest httpRequest) {
		Instant now = Instant.now();
		String clientKey = httpRequest != null ? httpRequest.getRemoteAddr() : null;
		return BookingResponse.from(
			bookings.book(customerId(jwt), request.showtimeId(), request.seatIds(), now, clientKey), now);
	}

	@GetMapping("/api/bookings")
	@Operation(summary = "List my bookings", description = "Returns a page of the authenticated customer's bookings, newest first. "
		+ "Defaults to page=0 and size=20 (max 100); totals are returned in X-Total-Count, X-Page, X-Page-Size, and "
		+ "X-Total-Pages headers. Requires CUSTOMER.")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	ResponseEntity<List<BookingResponse>> list(@AuthenticationPrincipal Jwt jwt,
		@Parameter(description = "Zero-based page index.", example = "0",
			schema = @Schema(minimum = "0", maximum = "100000", defaultValue = "0"))
		@RequestParam(defaultValue = "0") @Min(value = 0, message = "Page must be 0 or greater")
		@Max(value = PageQuery.MAX_PAGE, message = "Page must be at most 100000") int page,
		@Parameter(description = "Maximum number of items per page (1–100).", example = "20",
			schema = @Schema(minimum = "1", maximum = "100", defaultValue = "20"))
		@RequestParam(defaultValue = "20") @Min(value = 1, message = "Size must be at least 1")
		@Max(value = PageQuery.MAX_SIZE, message = "Size must be at most 100") int size) {
		Instant now = Instant.now();
		return PaginationHeaders.ok(bookings.list(customerId(jwt), new PageQuery(page, size)),
			booking -> BookingResponse.from(booking, now));
	}

	@GetMapping("/api/bookings/{id}")
	@Operation(summary = "Get my booking", description = "Returns a booking owned by the authenticated customer. Another "
		+ "customer's booking is indistinguishable from a missing booking and returns 404. Requires CUSTOMER.")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	BookingResponse get(@AuthenticationPrincipal Jwt jwt,
		@Parameter(description = "Booking UUID owned by the authenticated customer.",
			example = "550e8400-e29b-41d4-a716-446655440030") @PathVariable UUID id) {
		return BookingResponse.from(bookings.get(customerId(jwt), id), Instant.now());
	}

	public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	@PostMapping("/api/bookings/{id}/payment")
	@Operation(summary = "Pay for my booking", description = "Simulates payment for a live Pending booking. Supports an "
		+ "optional Idempotency-Key header so retried or concurrent requests return the same result without creating a "
		+ "duplicate transaction; reusing a key on a different booking returns 409. An expired hold returns 409; retrying "
		+ "payment for an already Confirmed booking returns it unchanged. Missing or another customer's booking returns "
		+ "404. Requires CUSTOMER.")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	BookingResponse pay(@AuthenticationPrincipal Jwt jwt,
		@Parameter(description = "Booking UUID owned by the authenticated customer.",
			example = "550e8400-e29b-41d4-a716-446655440030") @PathVariable UUID id,
		@Parameter(description = "Optional client-generated idempotency key (1–128 characters) to safely retry payment.",
			example = "pay-550e8400-e29b-41d4-a716-446655440030",
			schema = @Schema(type = "string", minLength = 1, maxLength = BookingService.MAX_IDEMPOTENCY_KEY_LENGTH))
		@RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
		Instant now = Instant.now();
		return BookingResponse.from(bookings.pay(customerId(jwt), id, now, idempotencyKey), now);
	}

	@DeleteMapping("/api/bookings/{id}")
	@Operation(summary = "Abandon my pending booking", description = "Deletes a Pending booking and releases its seats. "
		+ "Expired holds can be abandoned too. Confirmed or Refunded bookings cannot be cancelled by a customer and return 409. "
		+ "Missing or another customer's "
		+ "booking returns 404. Requires CUSTOMER.")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	void abandon(@AuthenticationPrincipal Jwt jwt,
		@Parameter(description = "Booking UUID owned by the authenticated customer.",
			example = "550e8400-e29b-41d4-a716-446655440030") @PathVariable UUID id) {
		bookings.abandon(customerId(jwt), id);
	}

	private static UUID customerId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	record BookingRequest(@NotNull(message = "Showtime is required")
		@Schema(description = "Showtime to book.", example = "550e8400-e29b-41d4-a716-446655440010") UUID showtimeId,
		@NotNull(message = "Seats are required")
		@ArraySchema(arraySchema = @Schema(description = "Choose 1–8 unique seat IDs for this showtime.",
			example = "[\"550e8400-e29b-41d4-a716-446655440020\"]"),
			schema = @Schema(type = "string", format = "uuid", example = "550e8400-e29b-41d4-a716-446655440020"),
			minItems = 1, maxItems = 8)
		List<@NotNull(message = "Seat ID is required") UUID> seatIds) {
	}

	record BookingResponse(
		@Schema(description = "Booking identifier.", example = "550e8400-e29b-41d4-a716-446655440030") UUID id,
		@Schema(description = "Booked showtime identifier.", example = "550e8400-e29b-41d4-a716-446655440010") UUID showtimeId,
		@Schema(description = "Booking state, including computed EXPIRED for elapsed holds.", example = "PENDING")
		BookingStatus status,
		@Schema(description = "Booking creation instant.", example = "2026-05-01T12:00:00Z") Instant createdAt,
		@Schema(description = "Hold expiry instant (10 minutes after creation).", example = "2026-05-01T12:10:00Z")
		Instant expiresAt,
		@ArraySchema(arraySchema = @Schema(description = "Seats and their prices copied when booked."),
			schema = @Schema(implementation = BookedSeat.class,
				example = "{\"seatId\":\"550e8400-e29b-41d4-a716-446655440020\",\"price\":100000}"))
		List<BookedSeat> seats,
		@Schema(description = "Total price in integer VND.", example = "100000") long totalPrice) {

		static BookingResponse from(Booking booking, Instant now) {
			return new BookingResponse(booking.id(), booking.showtimeId(), booking.statusAt(now), booking.createdAt(),
				booking.expiresAt(), booking.seats(), booking.totalPrice());
		}
	}
}
