package vn.edu.uet.cinema.business.booking;

import vn.edu.uet.cinema.business.ConflictException;

/** Thrown when a customer exceeds hold rate limits or repeated abandon limits to prevent seat hoarding (C2). */
public class SeatHoardingException extends ConflictException {

	public SeatHoardingException(String message) {
		super(message);
	}
}
