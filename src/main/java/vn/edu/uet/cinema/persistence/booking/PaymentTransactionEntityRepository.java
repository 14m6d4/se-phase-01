package vn.edu.uet.cinema.persistence.booking;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.uet.cinema.business.booking.PaymentTransactionType;

interface PaymentTransactionEntityRepository extends JpaRepository<PaymentTransactionEntity, UUID> {

	List<PaymentTransactionEntity> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);

	Optional<PaymentTransactionEntity> findByCustomerIdAndTypeAndIdempotencyKey(
		UUID customerId, PaymentTransactionType type, String idempotencyKey);
}
