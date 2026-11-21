-- Indexes for showtime seat occupancy, cascade deletes, and browse queries
-- Addresses Issue #2 (Seq Scan on booking and lock wait during contention/race)

-- 1. Eliminate Sequential Scan on booking in findOccupiedSeats, deletePendingBookings,
--    deleteLapsedHolds, and Showtime cancellation updates.
CREATE INDEX ix_booking_showtime_id ON booking (showtime_id);

-- 2. Speed up foreign key cascade (fk_booked_seat_booking ON DELETE CASCADE)
--    and booking seat lookups during abandon, hold expiration, and cancellation.
CREATE INDEX ix_booked_seat_booking_id ON booked_seat (booking_id);

-- 3. Speed up movie showtime lookups (browsing showtimes by movie) and FK constraint checks.
CREATE INDEX ix_showtime_movie_id ON showtime (movie_id);

-- 4. Speed up queries for customer booking history and profile checks.
CREATE INDEX ix_booking_customer_id ON booking (customer_id);

-- 5. Partial index for fast cleanup of expired pending holds (eliminates scanning confirmed bookings).
CREATE INDEX ix_booking_pending_expires_at ON booking (expires_at) WHERE status = 'PENDING';
