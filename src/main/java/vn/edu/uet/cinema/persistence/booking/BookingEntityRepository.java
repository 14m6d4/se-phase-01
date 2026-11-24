package vn.edu.uet.cinema.persistence.booking;

import static vn.edu.uet.cinema.persistence.booking.HoldSql.LAPSED_HOLD;
import static vn.edu.uet.cinema.persistence.booking.HoldSql.LIVE_HOLD;
import static vn.edu.uet.cinema.persistence.booking.HoldSql.OCCUPIES;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface BookingEntityRepository extends JpaRepository<BookingEntity, UUID> {

	@Query("select b from BookingEntity b left join fetch b.seats where b.id = :id")
	Optional<BookingEntity> findByIdWithSeats(UUID id);

	@Query(nativeQuery = true, value = "select b.* from booking b where b.customer_id = :customerId and " + LIVE_HOLD)
	Optional<BookingEntity> findLivePending(UUID customerId, Instant now);

	List<BookingEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

	Page<BookingEntity> findByCustomerIdOrderByCreatedAtDescIdDesc(UUID customerId, Pageable pageable);

	/** The conditional update of ADR 0004: only a live Hold can be paid. */
	@Modifying
	@Query(nativeQuery = true, value = "update booking b set status = 'CONFIRMED' where b.id = :id and " + LIVE_HOLD)
	int confirm(UUID id, Instant now);

	@Modifying
	@Query(nativeQuery = true, value = "update booking b set status = 'CONFIRMED' where b.id = :id and b.customer_id = :customerId and " + LIVE_HOLD)
	int confirm(UUID id, UUID customerId, Instant now);

	/** booked_seat rows go by ON DELETE CASCADE. */
	@Modifying
	@Query(nativeQuery = true, value = "delete from booking where id = :id and status = 'PENDING'")
	int deletePending(UUID id);

	@Modifying
	@Query(nativeQuery = true, value = "delete from booking where id = :id and customer_id = :customerId and status = 'PENDING'")
	int deletePending(UUID id, UUID customerId);

	@Query(nativeQuery = true, value = "select exists(select 1 from showtime where id = :showtimeId)")
	boolean existsShowtime(UUID showtimeId);

	/**
	 * Holds the Showtime row until commit, so a cancel waits for this Booking and then deletes it, or has already
	 * committed and is seen here. Null if the Showtime does not exist.
	 */
	@Query(nativeQuery = true, value = "select status from showtime where id = :showtimeId for share")
	String lockShowtimeStatus(UUID showtimeId);

	/**
	 * Locks the Showtime row exclusively for the duration of the hold transaction (ADR 0011).
	 * Serializes hold operations per showtime and synchronizes with cancelShowtime.
	 * Returns null if the Showtime does not exist.
	 */
	@Query(nativeQuery = true, value = "select status from showtime where id = :showtimeId for no key update")
	String lockShowtimeStatusForUpdate(UUID showtimeId);

	/**
	 * Strategy 3 (seat-rows): Atomically claims seats in ascending order.
	 * Returns the seat IDs that were successfully claimed (inserted or updated with booking_id).
	 */
	@Query(nativeQuery = true, value = """
		insert into showtime_seat (showtime_id, seat_id, booking_id)
		select :showtimeId, s, :bookingId
		from unnest(cast(:seatIds as uuid[])) s
		order by s
		on conflict (showtime_id, seat_id) do update
		  set booking_id = excluded.booking_id
		  where showtime_seat.booking_id is null
		returning seat_id
		""")
	List<UUID> claimSeats(UUID showtimeId, UUID[] seatIds, UUID bookingId);

	/** Lapsed Holds on these Seats, and the Customer's own lapsed one. booked_seat rows go by ON DELETE CASCADE. */
	@Modifying
	@Query(nativeQuery = true, value = """
		delete from booking b where (b.customer_id = :customerId or b.id in (select s.booking_id from booked_seat s
			where s.showtime_id = :showtimeId and s.seat_id in (:seatIds))) and
		""" + LAPSED_HOLD)
	void deleteLapsedHolds(UUID customerId, UUID showtimeId, Collection<UUID> seatIds, Instant now);

	/**
	 * Filters on {@code booked_seat.showtime_id}, which leads {@code uq_booked_seat_showtime_seat}, rather than on
	 * {@code booking.showtime_id}, which no index leads. The two are equal: {@code fk_booked_seat_booking} ties them.
	 */
	@Query(nativeQuery = true, value = """
		select s.seat_id as seatId, b.status as status from booked_seat s join booking b on b.id = s.booking_id
		where s.showtime_id = :showtimeId and
		""" + OCCUPIES)
	List<OccupiedSeat> findOccupiedSeats(UUID showtimeId, Instant now);

	/** Which of these Seats are taken at {@code now}: at most one index row per Seat, whatever the Showtime's size. */
	@Query(nativeQuery = true, value = """
		select s.seat_id from booked_seat s join booking b on b.id = s.booking_id
		where s.showtime_id = :showtimeId and s.seat_id in (:seatIds) and
		""" + OCCUPIES)
	List<UUID> findTakenSeats(UUID showtimeId, Collection<UUID> seatIds, Instant now);

	/** ADR 0004: lapsed Holds never block deleting their Showtime. booked_seat rows go by ON DELETE CASCADE. */
	@Modifying
	@Query(nativeQuery = true, value = "delete from booking b where b.showtime_id = :showtimeId and " + LAPSED_HOLD)
	void deleteLapsedHoldsOfShowtime(UUID showtimeId, Instant now);

	/** Fails on {@code fk_booking_showtime} while any Booking of it is left. */
	@Modifying
	@Query(nativeQuery = true, value = "delete from showtime where id = :showtimeId")
	int deleteShowtime(UUID showtimeId);

	@Modifying
	@Query(nativeQuery = true, value = """
		update showtime set status = 'CANCELLED', cancellation_reason = :reason, cancellation_note = :note
		where id = :id and status = 'SCHEDULED' and ends_at > :now
		""")
	int cancelShowtime(UUID id, String reason, String note, Instant now);

	/** Expired ones too: they are stored as PENDING. booked_seat rows go by ON DELETE CASCADE. */
	@Modifying
	@Query(nativeQuery = true, value = "delete from booking where showtime_id = :showtimeId and status = 'PENDING'")
	void deletePendingBookings(UUID showtimeId);

	@Modifying
	@Query(nativeQuery = true, value = """
		update booking set status = 'REFUNDED' where showtime_id = :showtimeId and status = 'CONFIRMED'
		""")
	void refundConfirmedBookings(UUID showtimeId);

	interface OccupiedSeat {

		UUID getSeatId();

		String getStatus();
	}
}
