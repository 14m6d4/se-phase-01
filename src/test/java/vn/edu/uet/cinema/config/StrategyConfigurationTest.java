package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SingleWriterSeatInventory;
import vn.edu.uet.cinema.persistence.booking.JpaSeatInventory;

class StrategyConfigurationTest {

	private final BusinessConfig businessConfig = new BusinessConfig();
	private final SeatInventory mockInventory = new SeatInventory() {
		public vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome hold(vn.edu.uet.cinema.business.showtime.inventory.Booking pending, java.time.Instant now) { return null; }
		public vn.edu.uet.cinema.business.showtime.inventory.PayOutcome pay(vn.edu.uet.cinema.business.showtime.inventory.Booking booking, java.time.Instant now) { return null; }
		public vn.edu.uet.cinema.business.showtime.inventory.AbandonOutcome abandon(vn.edu.uet.cinema.business.showtime.inventory.Booking booking) { return null; }
		public java.util.Map<java.util.UUID, vn.edu.uet.cinema.business.showtime.inventory.SeatStatus> statuses(java.util.UUID showtimeId, java.time.Instant now) { return java.util.Map.of(); }
		public vn.edu.uet.cinema.business.showtime.inventory.CancelOutcome cancel(java.util.UUID showtimeId, vn.edu.uet.cinema.business.showtime.inventory.Cancellation cancellation, java.time.Instant now) { return null; }
		public vn.edu.uet.cinema.business.showtime.inventory.DeleteOutcome delete(java.util.UUID showtimeId, java.time.Instant now) { return null; }
	};

	@Test
	void wiresJpaSeatInventoryForDatabaseStrategies() {
		SeatInventory inventory = businessConfig.seatInventory(mockInventory, "unique");
		assertThat(inventory).isSameAs(mockInventory);

		SeatInventory showtimeLockInventory = businessConfig.seatInventory(mockInventory, "showtime-lock");
		assertThat(showtimeLockInventory).isSameAs(mockInventory);

		SeatInventory seatRowsInventory = businessConfig.seatInventory(mockInventory, "seat-rows");
		assertThat(seatRowsInventory).isSameAs(mockInventory);
	}

	@Test
	void wrapsWithSingleWriterDecoratorWhenConfigured() {
		SeatInventory inventory = businessConfig.seatInventory(mockInventory, "single-writer");
		assertThat(inventory).isInstanceOf(SingleWriterSeatInventory.class);
	}

	@Test
	void failFastOnInvalidStrategyInBusinessConfig() {
		assertThatThrownBy(() -> businessConfig.seatInventory(mockInventory, "non-existent-strategy"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unknown seat inventory strategy: 'non-existent-strategy'");

		assertThatThrownBy(() -> businessConfig.seatInventory(mockInventory, ""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Seat inventory strategy must not be blank");
	}

	@Test
	void failFastOnInvalidStrategyInJpaSeatInventory() {
		assertThatThrownBy(() -> new JpaSeatInventory(null, TransactionOperations.withoutTransaction(), "illegal"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unknown seat inventory strategy: 'illegal'");
	}
}
