package vn.edu.uet.cinema.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import vn.edu.uet.cinema.business.account.AccountRepository;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.account.PasswordHasher;
import vn.edu.uet.cinema.business.auditorium.AuditoriumRepository;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.booking.BookingRepository;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.cinema.CinemaRepository;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.MovieRepository;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.ShowtimeRepository;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SingleWriterSeatInventory;

/** Business services carry no Spring annotations (ADR 0001), so they are wired here. */
@Configuration
class BusinessConfig {

	private static final Logger log = LoggerFactory.getLogger(BusinessConfig.class);

	@Bean
	AccountService accountService(AccountRepository accounts, PasswordHasher passwordHasher) {
		return new AccountService(accounts, passwordHasher);
	}

	@Bean
	MovieService movieService(MovieRepository movies, ShowtimeRepository showtimes) {
		return new MovieService(movies, showtimes);
	}

	@Bean
	CinemaService cinemaService(CinemaRepository cinemas, AuditoriumRepository auditoriums) {
		return new CinemaService(cinemas, auditoriums);
	}

	@Bean
	AuditoriumService auditoriumService(CinemaService cinemas, AuditoriumRepository auditoriums,
		ShowtimeRepository showtimes) {
		return new AuditoriumService(cinemas, auditoriums, showtimes);
	}

	@Bean
	@Primary
	SeatInventory seatInventory(@Qualifier("jpaSeatInventory") SeatInventory databaseInventory,
		@Value("${app.seat-inventory.strategy:unique}") String strategyValue) {
		SeatInventoryStrategy strategy = SeatInventoryStrategy.from(strategyValue);
		log.info("Configured seat inventory strategy: {}", strategy.value());
		if (strategy == SeatInventoryStrategy.SINGLE_WRITER) {
			return new SingleWriterSeatInventory(databaseInventory);
		}
		return databaseInventory;
	}

	@Bean
	ShowtimeService showtimeService(MovieService movies, AuditoriumService auditoriums, ShowtimeRepository showtimes,
		SeatInventory inventory) {
		return new ShowtimeService(movies, auditoriums, showtimes, inventory);
	}

	@Bean
	BookingService bookingService(ShowtimeService showtimes, BookingRepository bookings, SeatInventory inventory) {
		return new BookingService(showtimes, bookings, inventory);
	}

	/** Admins are never self-registered: the one Admin comes from ADMIN_EMAIL / ADMIN_PASSWORD. */
	@Bean
	ApplicationRunner adminBootstrap(AccountService accounts, @Value("${app.admin.email}") String email,
		@Value("${app.admin.password}") String password) {
		return args -> accounts.ensureAdmin(email, password);
	}
}
