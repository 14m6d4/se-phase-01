package vn.edu.uet.cinema.business.showtime.inventory;

import java.util.List;
import java.util.UUID;

/** What happened to a new Hold: it was taken, or why not. */
public sealed interface HoldOutcome {

	record Held(Booking booking) implements HoldOutcome {
	}

	/**
	 * The requested Seats that are HELD or SOLD, in request order. Empty when the Booking that took them was
	 * abandoned before they could be read: the Seats are free again and a retry may win.
	 */
	record SeatsTaken(List<UUID> seatIds) implements HoldOutcome {

		public SeatsTaken {
			seatIds = List.copyOf(seatIds);
		}
	}

	record ShowtimeCancelled() implements HoldOutcome {
	}

	/** The Showtime was deleted after the caller read it. */
	record ShowtimeGone() implements HoldOutcome {
	}

	/** The Customer already has a Pending Booking with a live Hold. */
	record CustomerHasPending() implements HoldOutcome {
	}

	/** The Hold lost a deadlock, and so did its retry. */
	record Busy() implements HoldOutcome {
	}
}
