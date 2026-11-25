package vn.edu.uet.cinema.business.showtime.inventory;

/** What happened to a payment, decided in the same transaction as the conditional update. */
public sealed interface PayOutcome {

	record Paid(Booking confirmed) implements PayOutcome {
	}

	/** It was Confirmed already, perhaps by a concurrent payment. */
	record AlreadyPaid(Booking current) implements PayOutcome {
	}

	/** Its status at the time of payment: Expired or Refunded. */
	record NotPayable(BookingStatus status) implements PayOutcome {
	}

	/** The supplied Idempotency-Key was already used for a different Booking. */
	record IdempotencyKeyConflict() implements PayOutcome {
	}

	/** The Booking no longer exists. */
	record Gone() implements PayOutcome {
	}
}
