package vn.edu.uet.cinema.business.showtime;

import vn.edu.uet.cinema.business.auditorium.Seat;

/** One Seat of a seat map: its price and its status at the time asked. */
public record SeatMapEntry(Seat seat, long price, SeatStatus status) {
}
