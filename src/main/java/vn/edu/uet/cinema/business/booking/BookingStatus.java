package vn.edu.uet.cinema.business.booking;

/** What is stored. An Expired Booking is a PENDING one past its {@code expiresAt}; it is never stored as such. */
public enum BookingStatus {
	PENDING, CONFIRMED
}
