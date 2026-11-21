package vn.edu.uet.cinema.persistence.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.booking.BookingRepository;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;

/** The read-only side of Bookings; every write is {@link JpaSeatInventory}'s. */
@Repository
class JpaBookingRepository implements BookingRepository {

	private final BookingEntityRepository jpa;

	JpaBookingRepository(BookingEntityRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Booking> findById(UUID id) {
		return jpa.findById(id).map(BookingEntity::toBooking);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Booking> findByCustomer(UUID customerId) {
		return jpa.findByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(BookingEntity::toBooking).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public PageSlice<Booking> findByCustomer(UUID customerId, PageQuery query) {
		Page<BookingEntity> page = jpa.findByCustomerIdOrderByCreatedAtDescIdDesc(
			customerId, PageRequest.of(query.page(), query.size()));
		return new PageSlice<>(page.getContent().stream().map(BookingEntity::toBooking).toList(),
			query.page(), query.size(), page.getTotalElements());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Booking> findLivePending(UUID customerId, Instant now) {
		return jpa.findLivePending(customerId, now).map(BookingEntity::toBooking);
	}
}
