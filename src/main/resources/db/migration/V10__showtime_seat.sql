CREATE TABLE showtime_seat (
    showtime_id uuid NOT NULL REFERENCES showtime(id) ON DELETE CASCADE,
    seat_id     uuid NOT NULL REFERENCES seat(id),
    booking_id  uuid NULL,
    PRIMARY KEY (showtime_id, seat_id),
    CONSTRAINT fk_showtime_seat_booking FOREIGN KEY (booking_id) REFERENCES booking(id)
        ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ix_showtime_seat_booking ON showtime_seat(booking_id);
