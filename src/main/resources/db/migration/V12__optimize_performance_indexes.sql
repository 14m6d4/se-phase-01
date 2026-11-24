-- Refine V11 indexes based on schema and query plan verification (P2).

-- 1. Drop redundant index: booked_seat PRIMARY KEY (booking_id, seat_id) in V6 already leads with booking_id.
DROP INDEX IF EXISTS ix_booked_seat_booking_id;

-- 2. Drop unused partial index: no query scans booking globally by expires_at alone.
DROP INDEX IF EXISTS ix_booking_pending_expires_at;

-- 3. Replace booking(customer_id) with composite key-order index matching P7 pagination
--    (ORDER BY created_at DESC, id DESC) and customer history reads.
DROP INDEX IF EXISTS ix_booking_customer_id;
CREATE INDEX ix_booking_customer_created_desc ON booking (customer_id, created_at DESC, id DESC);

-- 4. Replace booking(showtime_id) with (showtime_id, status, expires_at) to accelerate
--    deleteLapsedHoldsOfShowtime, deletePendingBookings, and refundConfirmedBookings.
DROP INDEX IF EXISTS ix_booking_showtime_id;
CREATE INDEX ix_booking_showtime_status_expires ON booking (showtime_id, status, expires_at);

-- 5. Partial composite index for browse's hottest query (GET /api/showtimes?date=...).
CREATE INDEX ix_showtime_scheduled_starts_at ON showtime (starts_at, id) WHERE status <> 'CANCELLED';
