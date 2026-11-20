package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeatInventoryStrategyTest {

	@Test
	void parsesValidStrategies() {
		assertThat(SeatInventoryStrategy.from("unique")).isEqualTo(SeatInventoryStrategy.UNIQUE);
		assertThat(SeatInventoryStrategy.from("showtime-lock")).isEqualTo(SeatInventoryStrategy.SHOWTIME_LOCK);
		assertThat(SeatInventoryStrategy.from("seat-rows")).isEqualTo(SeatInventoryStrategy.SEAT_ROWS);
		assertThat(SeatInventoryStrategy.from("single-writer")).isEqualTo(SeatInventoryStrategy.SINGLE_WRITER);
	}

	@Test
	void parsesCaseInsensitiveAndTrimmed() {
		assertThat(SeatInventoryStrategy.from("  UNIQUE  ")).isEqualTo(SeatInventoryStrategy.UNIQUE);
		assertThat(SeatInventoryStrategy.from("Showtime-Lock")).isEqualTo(SeatInventoryStrategy.SHOWTIME_LOCK);
		assertThat(SeatInventoryStrategy.from("  seat-rows ")).isEqualTo(SeatInventoryStrategy.SEAT_ROWS);
		assertThat(SeatInventoryStrategy.from("SINGLE-WRITER")).isEqualTo(SeatInventoryStrategy.SINGLE_WRITER);
	}

	@ParameterizedTest
	@ValueSource(strings = {"invalid", "optimistic", "redis", "row-lock", "none"})
	void throwsOnUnknownStrategy(String unknown) {
		assertThatThrownBy(() -> SeatInventoryStrategy.from(unknown))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unknown seat inventory strategy")
			.hasMessageContaining(unknown);
	}

	@Test
	void throwsOnNullOrBlank() {
		assertThatThrownBy(() -> SeatInventoryStrategy.from(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Seat inventory strategy must not be blank");

		assertThatThrownBy(() -> SeatInventoryStrategy.from(""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Seat inventory strategy must not be blank");

		assertThatThrownBy(() -> SeatInventoryStrategy.from("   "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Seat inventory strategy must not be blank");
	}
}
