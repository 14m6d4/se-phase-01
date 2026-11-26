package vn.edu.uet.cinema.persistence.booking;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import vn.edu.uet.cinema.business.booking.PaymentTransaction;
import vn.edu.uet.cinema.business.booking.PaymentTransactionRepository;
import vn.edu.uet.cinema.business.booking.PaymentTransactionType;

@Repository
class JpaPaymentTransactionRepository implements PaymentTransactionRepository {

	private final PaymentTransactionEntityRepository jpa;

	JpaPaymentTransactionRepository(PaymentTransactionEntityRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	public List<PaymentTransaction> findByBookingId(UUID bookingId) {
		return jpa.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
			.map(PaymentTransactionEntity::toPaymentTransaction)
			.toList();
	}

	@Override
	public Optional<PaymentTransaction> findByCustomerAndKey(UUID customerId, PaymentTransactionType type,
		String idempotencyKey) {
		return jpa.findByCustomerIdAndTypeAndIdempotencyKey(customerId, type, idempotencyKey)
			.map(PaymentTransactionEntity::toPaymentTransaction);
	}
}
