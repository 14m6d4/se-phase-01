package vn.edu.uet.cinema.persistence.booking;

import java.time.Instant;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome;

/**
 * Variation point for seat reservation claim strategies behind {@link JpaSeatInventory} (ADR 0010).
 */
@FunctionalInterface
interface Claim {

	HoldOutcome hold(Booking booking, Instant now);
}
