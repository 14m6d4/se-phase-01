package vn.edu.uet.cinema.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.account.InvalidCredentialsException;
import vn.edu.uet.cinema.business.auditorium.InvalidSeatLayoutException;
import vn.edu.uet.cinema.business.showtime.InvalidShowtimeException;

/**
 * Maps business exceptions to RFC 9457 problem details. Framework exceptions (validation,
 * malformed JSON, ...) are handled by Spring Boot via {@code spring.mvc.problemdetails.enabled}.
 */
@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler
	ProblemDetail notFound(NotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler
	ProblemDetail conflict(ConflictException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler
	ProblemDetail invalidSeatLayout(InvalidSeatLayoutException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler
	ProblemDetail invalidShowtime(InvalidShowtimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler
	ProblemDetail invalidCredentials(InvalidCredentialsException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
	}
}
