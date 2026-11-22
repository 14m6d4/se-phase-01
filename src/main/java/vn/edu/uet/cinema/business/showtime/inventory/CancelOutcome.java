package vn.edu.uet.cinema.business.showtime.inventory;

/** What happened to a cancellation. */
public sealed interface CancelOutcome {

	record Cancelled() implements CancelOutcome {
	}

	/** It was Cancelled already, or had ended. */
	record NotCancellable() implements CancelOutcome {
	}

	/** Showtime does not exist. */
	record NotFound() implements CancelOutcome {
	}
}
