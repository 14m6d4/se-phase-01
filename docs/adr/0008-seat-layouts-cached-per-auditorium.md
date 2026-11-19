# Seat layouts cached per Auditorium

English | [Tiếng Việt](0008-seat-layouts-cached-per-auditorium.vi.md)

Every seat map and every booking attempt prices the Seats of the Showtime's Auditorium. Phase 1 read the Auditorium and its Seats from the database each time, and that read was the most expensive statement of the browse load test and the top statement of book too. `ShowtimeService` now keeps each Auditorium's Seats in memory after the first read, in a `ConcurrentHashMap` keyed by Auditorium id. Prices are still computed per Showtime from its base price.

The cache is never invalidated, because the layout behind a live Showtime cannot change. Auditoriums have no update operation, `AuditoriumService.create` always saves under a fresh random id, and an Auditorium that has a Showtime cannot be deleted ([ADR 0006](0006-delete-guards-in-business-and-database.md)). The cache is filled only from a Showtime, so every entry belongs to an Auditorium that had a Showtime and was never edited. This holds on any number of instances: there is nothing to keep in step between them.

## Consequences

- A future feature that edits an Auditorium's Seats, or reuses an Auditorium id, must evict that Auditorium from the cache in the same change.
- Existence checks (`ShowtimeService.create`, `AuditoriumService.get`) still go to the database, so they see deletes.
- An entry can outlive its Auditorium. A request that loaded its Showtime just before the Showtime and then the Auditorium were deleted can read the entry once more: a booking then fails on the Showtime's foreign key (409), and a seat map returns what a read just before the delete would have.
- Memory grows by one entry per Auditorium ever priced. With the handful of Auditoriums a cinema chain has, entries of deleted ones are not worth evicting.
