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
	 * A Booking that keeps its Seats from anyone else at {@code :now}. A Refunded Booking whose Hold has not lapsed
	 * still matches through {@code expires_at} (register entry B2).
	 */
	public static final String OCCUPIES = "(b.status = 'CONFIRMED' or b.expires_at > :now)";

	private HoldSql() {
	}
}
