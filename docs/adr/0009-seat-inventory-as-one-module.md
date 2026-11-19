# Seat Inventory as one module

English | [Tiếng Việt](0009-seat-inventory-as-one-module.vi.md)

Amends [ADR 0005](0005-showtime-cancellation-in-one-adapter-transaction.md). Keeps [ADR 0001](0001-framework-free-business-layer.md) and [ADR 0004](0004-seat-contention-via-unique-constraint-and-lazy-holds.md).

A Showtime's Seat Inventory used to be spread over both business modules and both adapters: the Showtime adapter deleted and refunded Booking rows when it cancelled or deleted a Showtime, the Booking adapter locked the Showtime row when it took a Hold, the seat-map port was declared in `business.showtime` and implemented in `persistence.booking`, and the in-memory fakes shared one map of Booking rows. Each contention strategy of phase 2 needs one place that sees every change to a Seat, so all of them now go through one port, `SeatInventory`, in `business.showtime.inventory`:

| Operation | What it does in one transaction | Outcome |
|---|---|---|
| `hold` | `SELECT … FOR SHARE` on the Showtime first, delete lapsed Holds, insert the Booking; a lost deadlock is retried once | `Held`, `SeatsTaken`, `ShowtimeCancelled`, `ShowtimeGone`, `CustomerHasPending`, `Busy` |
| `pay` | conditional update to Confirmed | `Paid`, `AlreadyPaid`, `NotPayable`, `Gone` |
| `abandon` | conditional delete of a Pending Booking | `Abandoned`, `NotPending`, `Gone` |
| `statuses` | read the HELD and SOLD Seats | a map of Seat to status |
| `cancel` | the three statements of ADR 0005, in its order | `Cancelled`, `NotCancellable` |
| `delete` | delete lapsed Holds, then the Showtime | `Deleted`, `HasBookings` |

The rule is still one use case, one repository method, one transaction. The outcomes are sealed interfaces of records, so the business layer stays framework-free, and the services map each outcome to the exception and message clients already get. When a conditional update or delete changes nothing, the adapter reads the row again **in the same transaction** and returns why. Under READ COMMITTED that read sees a concurrent payment the update waited for. A single statement with a data-modifying CTE would not: its outer select shares the snapshot taken before the wait. The exception is the seat conflict of `hold`: PostgreSQL aborts the transaction on the unique violation, so the taken Seats are still read after the rollback, as before.

`Booking`, `BookedSeat`, `BookingStatus` and `SeatStatus` moved into the inventory package. `BookingRepository` keeps only its reads, `ShowtimeRepository` lost `cancel` and `deleteById`, and `SeatOccupancyReader` is gone. Behind the port is one adapter, `JpaSeatInventory`, next to `BookingEntity`. Its one fake, `InMemorySeatInventory`, owns the only Booking map and changes Showtimes through two test helpers of `InMemoryShowtimes`.

The live-Hold rule is now written once in Java (`Booking.statusAt`, `Booking.occupiesSeatsAt`) and once in SQL (`HoldSql.LIVE_HOLD`, `LAPSED_HOLD`, `OCCUPIES`), which every query concatenates. A Refunded Booking's Seats count as SOLD, which is what the unique constraint still refuses to anyone else. Before, they counted as HELD until the Hold time passed and then as free (register entry B2).

**Amendment to ADR 0005.** Cancellation's transaction moves from `ShowtimeRepository.cancel` to `SeatInventory.cancel` (`JpaSeatInventory.cancel`). Its three statements and their order are unchanged, and every `hold` still starts with the `FOR SHARE` statement. Everything else ADR 0005 says still holds.

## Consequences

- Each future contention strategy is another way to implement `hold`, or a decorator over the port. `pay`, `abandon`, `statuses`, `cancel` and `delete` are shared. The unique constraint stays the last guard (ADR 0004).
- Business modules: `business.booking` depends on `business.showtime`, never the reverse. Inside the Showtime module, `inventory` uses `Cancellation` and `ShowtimeService` uses the port, so the two packages depend on each other. A cycle rule must treat `business.showtime..` as one slice.
- Deleting a Showtime is now a native `delete from showtime`, without the select Spring Data's `deleteById` ran first. The `fk_booking_showtime` guard and its `409` are unchanged.
- The port holds no in-memory state, so rule 8 does not apply to it.
