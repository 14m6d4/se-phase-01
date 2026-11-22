package vn.edu.uet.cinema.business.showtime.inventory;

/** What happened to a Showtime delete. */
public sealed interface DeleteOutcome {

	record Deleted() implements DeleteOutcome {
	}

	/** It has a Confirmed, Refunded or live Pending Booking. */
	record HasBookings() implements DeleteOutcome {
	}

	/** Showtime does not exist. */
	record NotFound() implements DeleteOutcome {
	}
}
