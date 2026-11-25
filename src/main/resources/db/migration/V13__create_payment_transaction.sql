-- S3: Explicit payment & refund ledger with database-enforced idempotency constraints.
-- Tracks PAYMENT (COMPLETED or REFUNDED on late payment after Hold expiry) and REFUND (showtime cancellation)
-- without blocking hard-deletion of lapsed Pending Bookings (ADR 0004).
CREATE TABLE payment_transaction (
    id              uuid         PRIMARY KEY,
    booking_id      uuid         NOT NULL,
    customer_id     uuid         NOT NULL CONSTRAINT fk_payment_tx_customer REFERENCES account (id),
    type            varchar(16)  NOT NULL CHECK (type IN ('PAYMENT', 'REFUND')),
    status          varchar(24)  NOT NULL CHECK (status IN ('COMPLETED', 'REFUNDED')),
    amount          bigint       NOT NULL CHECK (amount > 0),
    idempotency_key varchar(128),
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL
);

-- At most one completed payment charge per Booking.
CREATE UNIQUE INDEX uq_payment_tx_booking_payment
    ON payment_transaction (booking_id)
    WHERE type = 'PAYMENT' AND status = 'COMPLETED';

-- At most one cancellation refund per Booking.
CREATE UNIQUE INDEX uq_payment_tx_booking_refund
    ON payment_transaction (booking_id)
    WHERE type = 'REFUND' AND status = 'REFUNDED';

-- Idempotency-Key is unique per Customer and transaction type when supplied.
CREATE UNIQUE INDEX uq_payment_tx_customer_idempotency_key
    ON payment_transaction (customer_id, type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_payment_tx_booking_id ON payment_transaction (booking_id);
