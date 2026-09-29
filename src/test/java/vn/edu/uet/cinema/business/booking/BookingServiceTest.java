package vn.edu.uet.cinema.business.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.auditorium.Seat;
import vn.edu.uet.cinema.business.auditorium.SeatType;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;

class BookingServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

	private final ShowtimeService showtimes = mock(ShowtimeService.class);
	private final AuditoriumService auditoriums = mock(AuditoriumService.class);
	private final BookingRepository repository = mock(BookingRepository.class);
	private final BookingService service = new BookingService(showtimes, auditoriums, repository);

	private final UUID customerId = UUID.randomUUID();
	private final Seat standard = new Seat(UUID.randomUUID(), "A", 1, SeatType.STANDARD);
	private final Seat vip = new Seat(UUID.randomUUID(), "B", 1, SeatType.VIP);
	private final Seat couple = new Seat(UUID.randomUUID(), "C", 1, SeatType.COUPLE);
	private final Showtime showtime = showtime(Instant.parse("2026-10-01T12:00:00Z"));

	@Test
	void newBookingIsPendingWithTenMinuteHoldAndCopiedSeatPrices() {
		when(repository.saveNew(any(Booking.class), eq(NOW))).thenAnswer(invocation -> invocation.getArgument(0));

		Booking booking = service.book(customerId, showtime.id(), List.of(standard.id(), vip.id(), couple.id()), NOW);

		assertThat(booking.customerId()).isEqualTo(customerId);
		assertThat(booking.showtimeId()).isEqualTo(showtime.id());
		assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
		assertThat(booking.expiresAt()).isEqualTo(Instant.parse("2026-10-01T10:10:00Z"));
		assertThat(booking.seats()).containsExactly(new BookedSeat(standard.id(), 100_000),
			new BookedSeat(vip.id(), 120_000), new BookedSeat(couple.id(), 210_000));
		assertThat(booking.totalPrice()).isEqualTo(430_000);
	}

	@Test
	void seatsMustBeOneToEightDistinctSeatsOfTheShowtimesAuditorium() {
		List<UUID> nine = Stream.generate(UUID::randomUUID).limit(9).toList();
		for (List<UUID> seatIds : List.of(List.<UUID>of(), nine, List.of(standard.id(), standard.id()),
			List.of(standard.id(), UUID.randomUUID()))) {
			assertThatThrownBy(() -> service.book(customerId, showtime.id(), seatIds, NOW))
				.as("%s", seatIds).isInstanceOf(InvalidBookingException.class);
		}
		verifyNoInteractions(repository);
	}

	@Test
	void showtimeThatHasStartedCannotBeBooked() {
		Showtime started = showtime(NOW);

		assertThatThrownBy(() -> service.book(customerId, started.id(), List.of(standard.id()), NOW))
			.isInstanceOf(ConflictException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void customerWithLivePendingBookingIsToldItsId() {
		UUID pendingId = UUID.randomUUID();
		when(repository.findLivePending(customerId, NOW)).thenReturn(Optional.of(new Booking(pendingId, customerId,
			showtime.id(), BookingStatus.PENDING, NOW, NOW.plusSeconds(60), List.of(new BookedSeat(vip.id(), 1)))));

		assertThatThrownBy(() -> service.book(customerId, showtime.id(), List.of(standard.id()), NOW))
			.isInstanceOf(ConflictException.class).hasMessageContaining(pendingId.toString());
	}

	private Showtime showtime(Instant startsAt) {
		UUID auditoriumId = UUID.randomUUID();
		Showtime created = new Showtime(UUID.randomUUID(), UUID.randomUUID(), auditoriumId, startsAt,
			startsAt.plusSeconds(7_200), 100_000);
		when(showtimes.get(created.id())).thenReturn(created);
		when(auditoriums.get(auditoriumId)).thenReturn(new Auditorium(auditoriumId, UUID.randomUUID(), "Hall", 3, 1,
			List.of(standard, vip, couple)));
		return created;
	}
}
