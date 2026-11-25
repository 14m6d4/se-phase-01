package vn.edu.uet.cinema.persistence.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import vn.edu.uet.cinema.business.booking.PaymentTransaction;
import vn.edu.uet.cinema.business.booking.PaymentTransactionStatus;
import vn.edu.uet.cinema.business.booking.PaymentTransactionType;

@Entity
@Table(name = "payment_transaction")
class PaymentTransactionEntity {

	@Id
	private UUID id;
	@Column(name = "booking_id", nullable = false)
	private UUID bookingId;
	@Column(name = "customer_id", nullable = false)
	private UUID customerId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private PaymentTransactionType type;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private PaymentTransactionStatus status;
	@Column(nullable = false)
	private long amount;
	@Column(name = "idempotency_key", length = 128)
	private String idempotencyKey;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PaymentTransactionEntity() {
	}

	PaymentTransaction toPaymentTransaction() {
		return new PaymentTransaction(id, bookingId, customerId, type, status, amount, idempotencyKey, createdAt,
			updatedAt);
	}
}
