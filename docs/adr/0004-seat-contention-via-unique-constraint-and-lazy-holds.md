# Seat contention via unique constraint and lazily expired Holds

Double booking is prevented by a plain `UNIQUE(showtime_id, seat_id)` on booked seats, not by application locks: the insert that loses the race fails and is reported as "seat already taken". To keep that constraint plain, Pending Bookings that are abandoned or Expired are deleted rather than kept with a status, and expiry is lazy — there is no scheduler. When someone books a Seat, the same transaction first deletes any lapsed Hold on it; seat maps filter by `expires_at > now()`; payment is a conditional update that only succeeds while the Hold is live.

## Consequences

- No history of abandoned or Expired Bookings. Acceptable: nothing reads it.
- Lapsed Holds on Seats nobody re-books linger as rows until touched; they never block anyone.
- Pessimistic/optimistic locking and queue-based booking were deliberately left out as phase 2 comparison points.
