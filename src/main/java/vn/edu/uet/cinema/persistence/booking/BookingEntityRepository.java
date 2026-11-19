package vn.edu.uet.cinema.persistence.booking;

import static vn.edu.uet.cinema.persistence.HoldSql.LAPSED_HOLD;
import static vn.edu.uet.cinema.persistence.HoldSql.LIVE_HOLD;
import static vn.edu.uet.cinema.persistence.HoldSql.OCCUPIES;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface BookingEntityRepository extends JpaRepository<BookingEntity, UUID> {

	@Query(nativeQuery = true, value = "select b.* from booking b where b.customer_id = :customerId and " + LIVE_HOLD)
	Optional<BookingEntity> findLivePending(UUID customerId, Instant now);

	List<BookingEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

	/** The conditional update of ADR 0004: only a live Hold can be paid. */
	@Modifying
	@Query(nativeQuery = true, value = "update booking b set status = 'CONFIRMED' where b.id = :id and " + LIVE_HOLD)
	int confirm(UUID id, Instant now);

	/** booked_seat rows go by ON DELETE CASCADE. */
	@Modifying
	@Query(nativeQuery = true, value = "delete from booking where id = :id and status = 'PENDING'")
	int deletePending(UUID id);

	/**
	 * Holds the Showtime row until commit, so a cancel waits for this Booking and then deletes it, or has already
	 * committed and is seen here. Null if the Showtime does not exist.
	 */
	@Query(nativeQuery = true, value = "select status from showtime where id = :showtimeId for share")
	String lockShowtimeStatus(UUID showtimeId);

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

	interface OccupiedSeat {

		UUID getSeatId();

		String getStatus();
	}
}
