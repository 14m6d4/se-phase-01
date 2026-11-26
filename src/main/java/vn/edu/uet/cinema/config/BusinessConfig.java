package vn.edu.uet.cinema.config;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import vn.edu.uet.cinema.business.account.AccountRepository;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.account.PasswordHasher;
import vn.edu.uet.cinema.business.account.RefreshTokenRepository;
import vn.edu.uet.cinema.business.account.RefreshTokenService;
import vn.edu.uet.cinema.business.auditorium.AuditoriumRepository;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.booking.AntiHoardingGuard;
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

	private final BookingMetrics bookingMetrics;

	BusinessConfig() {
		this.bookingMetrics = BookingMetrics.noop();
	}

	@Autowired
	BusinessConfig(ObjectProvider<BookingMetrics> bookingMetrics) {
		this.bookingMetrics = bookingMetrics.getIfAvailable(BookingMetrics::noop);
	}

	@Bean
	AccountService accountService(AccountRepository accounts, PasswordHasher passwordHasher) {
		return new AccountService(accounts, passwordHasher);
	}

	@Bean
	RefreshTokenService refreshTokenService(RefreshTokenRepository refreshTokens, AccountRepository accounts,
		@Value("${app.jwt.refresh-token-ttl:7d}") Duration refreshTokenTtl) {
		return new RefreshTokenService(refreshTokens, accounts, refreshTokenTtl);
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
			return new SingleWriterSeatInventory(databaseInventory, 2,
				waitNanos -> bookingMetrics.recordLockWait("single_writer_lane", strategy.value(), waitNanos));
		}
		return databaseInventory;
	}

	@Bean
	ShowtimeService showtimeService(MovieService movies, AuditoriumService auditoriums, ShowtimeRepository showtimes,
		SeatInventory inventory) {
		return new ShowtimeService(movies, auditoriums, showtimes, inventory);
	}

	@Bean
	BookingService bookingService(ShowtimeService showtimes, BookingRepository bookings, SeatInventory inventory,
		@Value("${app.anti-hoarding.enabled:true}") boolean antiHoardingEnabled,
		@Value("${app.anti-hoarding.abandon-cooldown-seconds:5}") long abandonCooldownSeconds,
		@Value("${app.anti-hoarding.max-abandons-in-window:3}") int maxAbandonsInWindow,
		@Value("${app.anti-hoarding.repeated-abandon-penalty-seconds:120}") long repeatedAbandonPenaltySeconds,
		@Value("${app.anti-hoarding.max-active-holds-per-ip:3}") int maxActiveHoldsPerIp) {
		return new BookingService(showtimes, bookings, inventory,
			new AntiHoardingGuard(antiHoardingEnabled, Duration.ofSeconds(abandonCooldownSeconds),
				maxAbandonsInWindow, Duration.ofSeconds(repeatedAbandonPenaltySeconds), maxActiveHoldsPerIp));
	}

	/** Admins are never self-registered: the one Admin comes from ADMIN_EMAIL / ADMIN_PASSWORD. */
	@Bean
	ApplicationRunner adminBootstrap(AccountService accounts, @Value("${app.admin.email}") String email,
		@Value("${app.admin.password}") String password) {
		return args -> accounts.ensureAdmin(email, password);
	}
}
