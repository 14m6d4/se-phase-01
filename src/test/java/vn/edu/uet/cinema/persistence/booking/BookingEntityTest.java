package vn.edu.uet.cinema.persistence.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Persistable;
import vn.edu.uet.cinema.business.showtime.inventory.BookedSeat;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;

class BookingEntityTest {

	@Test
	void fromMarksEntityAsNewToAvoidMergeSelectBeforeInsert() {
		Instant now = Instant.parse("2026-10-01T10:00:00Z");
		UUID seat1 = UUID.fromString("00000000-0000-0000-0000-000000000002");
		UUID seat2 = UUID.fromString("00000000-0000-0000-0000-000000000001");
		Booking booking = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			BookingStatus.PENDING, now, now.plus(10, ChronoUnit.MINUTES),
			List.of(new BookedSeat(seat1, 100_000), new BookedSeat(seat2, 120_000)));

		BookingEntity entity = BookingEntity.from(booking);

		assertThat(entity).isInstanceOf(Persistable.class);
		assertThat(entity.isNew()).isTrue();
		assertThat(entity.getId()).isEqualTo(booking.id());
		assertThat(entity.toBooking().seats()).containsExactly(
			new BookedSeat(seat2, 120_000),
			new BookedSeat(seat1, 100_000));

		entity.markNotNew();
		assertThat(entity.isNew()).isFalse();
	}

	@Test
	void defaultEntityIsNotNewForJpaReloads() {
		BookingEntity entity = new BookingEntity();
		assertThat(entity.isNew()).isFalse();
	}
}
