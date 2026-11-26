package vn.edu.uet.cinema.business.booking;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read port for payment and refund ledger entries; writes happen atomically inside {@code SeatInventory} (ADR 0009). */
public interface PaymentTransactionRepository {

	List<PaymentTransaction> findByBookingId(UUID bookingId);

	Optional<PaymentTransaction> findByCustomerAndKey(UUID customerId, PaymentTransactionType type, String idempotencyKey);
}
