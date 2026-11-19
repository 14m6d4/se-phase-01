package vn.edu.uet.cinema.business.booking;

/** What happened to an abandon, decided in the same transaction as the conditional delete. */
public sealed interface AbandonOutcome {

	record Abandoned() implements AbandonOutcome {
	}

	/** Its stored status: Confirmed or Refunded. */
	record NotPending(BookingStatus status) implements AbandonOutcome {
	}

	/** The Booking no longer exists. */
	record Gone() implements AbandonOutcome {
	}
}
