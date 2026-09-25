# Cinema Booking

Backend for a cinema chain: admins publish what is showing where, customers book seats for a specific screening.

## Language

### Catalogue

**Cinema**:
One physical venue of the chain, identified by name and address.
_Avoid_: Theater, branch, location

**Auditorium**:
A screening room inside exactly one Cinema, with a fixed seat layout.
_Avoid_: Room, hall, screen

**Seat**:
A fixed position in an Auditorium, identified by row letter and number (e.g. `C7`), with a Seat Type.
_Avoid_: Chair, place

**Seat Type**:
The class of a Seat — Standard, VIP or Couple — which affects its price. A Couple seat is one sellable unit for two people.
_Avoid_: Seat class, category

**Movie**:
A film the chain can screen, with a known running time.
_Avoid_: Film, title

**Showtime**:
One scheduled screening of a Movie in an Auditorium at a specific start time.
_Avoid_: Screening, session, show

**Base Price**:
The price of a Standard Seat for a given Showtime; VIP and Couple prices are derived from it by fixed surcharges.
_Avoid_: Ticket price, fare

### Booking

**Booking**:
A Customer's reservation of one or more Seats for a single Showtime. Starts Pending and becomes Confirmed once paid.
_Avoid_: Order, reservation, ticket

**Hold**:
The time-limited claim a Pending Booking has on its Seats; when it lapses the Seats are free again.
_Avoid_: Lock, reservation

**Pending**:
A Booking whose Hold is running and which has not been paid yet. A Customer can abandon it; it then disappears and its Seats are free.

**Expired**:
A Pending Booking whose Hold has lapsed. It can no longer be paid and is eventually removed.

**Confirmed**:
A paid Booking. Final: it can never be cancelled or refunded; a Customer who can't attend passes it on themselves.

### People

**Customer**:
A registered person who books seats for Showtimes.
_Avoid_: User, client, member

**Admin**:
Staff who manage Cinemas, Auditoriums, Movies and Showtimes. Created out of band, never self-registered.
_Avoid_: Manager, staff, operator
