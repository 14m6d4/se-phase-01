package vn.edu.uet.cinema.business.booking;

import java.time.Instant;
import java.util.UUID;

/** Immutable ledger entry for a payment charge or refund (S3). */
public record PaymentTransaction(
	UUID id,
	UUID bookingId,
	UUID customerId,
	PaymentTransactionType type,
	PaymentTransactionStatus status,
	long amount,
	String idempotencyKey,
	Instant createdAt,
	Instant updatedAt) {
}
