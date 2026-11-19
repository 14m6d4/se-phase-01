package vn.edu.uet.cinema.persistence;

/**
 * The live-Hold rule in SQL, for native queries that alias {@code booking} as {@code b} and bind {@code :now}. It is
 * the SQL twin of {@code Booking.statusAt} and {@code Booking.occupiesSeatsAt}: change them together.
 */
public final class HoldSql {

	/** A Pending Booking whose Hold is still running at {@code :now}. */
	public static final String LIVE_HOLD = "b.status = 'PENDING' and b.expires_at > :now";

	/** A Pending Booking whose Hold has lapsed at {@code :now} (Expired): the complement of {@link #LIVE_HOLD}. */
	public static final String LAPSED_HOLD = "b.status = 'PENDING' and b.expires_at <= :now";

	/**
	 * A Booking that keeps its Seats from anyone else at {@code :now}: Confirmed, Refunded or a live Hold. These are
	 * exactly the rows {@code uq_booked_seat_showtime_seat} still refuses a new Booking for, once lapsed Holds are gone.
	 */
	public static final String OCCUPIES = "(b.status in ('CONFIRMED', 'REFUNDED') or (" + LIVE_HOLD + "))";

	private HoldSql() {
	}
}
