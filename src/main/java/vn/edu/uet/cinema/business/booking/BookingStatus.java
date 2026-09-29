package vn.edu.uet.cinema.business.booking;

/** PENDING and CONFIRMED are stored. EXPIRED is a PENDING Booking past its {@code expiresAt}, never stored. */
public enum BookingStatus {
	PENDING, CONFIRMED, EXPIRED
}
