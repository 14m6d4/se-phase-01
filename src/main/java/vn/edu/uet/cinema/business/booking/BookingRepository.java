package vn.edu.uet.cinema.business.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;

/**
 * Reads of Bookings, the same under every contention strategy. Every change to a Booking goes through
 * {@link vn.edu.uet.cinema.business.showtime.inventory.SeatInventory}.
 */
public interface BookingRepository {

	Optional<Booking> findById(UUID id);

	/** Newest first. */
	List<Booking> findByCustomer(UUID customerId);

	/** Newest first, paginated. */
	PageSlice<Booking> findByCustomer(UUID customerId, PageQuery query);

	/** The Customer's Pending Booking whose Hold is still running at {@code now}. */
	Optional<Booking> findLivePending(UUID customerId, Instant now);
}
