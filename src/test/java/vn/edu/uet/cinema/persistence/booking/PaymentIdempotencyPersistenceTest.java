package vn.edu.uet.cinema.persistence.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.booking.BookingRepository;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.booking.PaymentTransaction;
import vn.edu.uet.cinema.business.booking.PaymentTransactionRepository;
import vn.edu.uet.cinema.business.booking.PaymentTransactionStatus;
import vn.edu.uet.cinema.business.booking.PaymentTransactionType;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;
import vn.edu.uet.cinema.business.showtime.inventory.CancellationReason;
import vn.edu.uet.cinema.business.showtime.inventory.PayOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;

/**
 * Persistence and concurrency tests for payment & refund idempotency (Issue #17 / S3) against PostgreSQL.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentIdempotencyPersistenceTest {

	@Autowired BookingService bookings;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatInventory inventory;
	@Autowired PaymentTransactionRepository paymentTransactions;
	@Autowired BookingEntityRepository jpa;
	@Autowired TransactionOperations transaction;
	@Autowired AccountService accounts;
	@Autowired MovieService movies;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;
	@Autowired ShowtimeService showtimes;

	private final Instant now = Instant.now();

	@Test
	void concurrentPaymentsWithSameIdempotencyKeyCreateExactlyOnePaymentTransaction() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID customerId = customer();
		Booking pending = bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()), now);
		String key = "concurrent-same-" + UUID.randomUUID();

		int threads = 8;
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<Booking>> calls = IntStream.range(0, threads).<Callable<Booking>>mapToObj(i -> () -> {
			start.await();
			return bookings.pay(customerId, pending.id(), now, key);
		}).toList();

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Booking>> futures = calls.stream().map(pool::submit).toList();
			start.countDown();
			for (Future<Booking> future : futures) {
				Booking result = future.get();
				assertThat(result.id()).isEqualTo(pending.id());
				assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
			}
		} finally {
			pool.shutdownNow();
		}

		List<PaymentTransaction> txs = paymentTransactions.findByBookingId(pending.id());
		assertThat(txs).hasSize(1);
		assertThat(txs.get(0).type()).isEqualTo(PaymentTransactionType.PAYMENT);
		assertThat(txs.get(0).status()).isEqualTo(PaymentTransactionStatus.COMPLETED);
		assertThat(txs.get(0).amount()).isEqualTo(pending.totalPrice());
		assertThat(txs.get(0).idempotencyKey()).isEqualTo(key);
	}

	@Test
	void concurrentPaymentsWithDifferentKeysOnSameBookingCreateExactlyOneCharge() throws Exception {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID customerId = customer();
		Booking pending = bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()), now);

		int threads = 6;
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<Booking>> calls = IntStream.range(0, threads).<Callable<Booking>>mapToObj(i -> () -> {
			start.await();
			return bookings.pay(customerId, pending.id(), now, "diff-key-" + i + "-" + UUID.randomUUID());
		}).toList();

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Booking>> futures = calls.stream().map(pool::submit).toList();
			start.countDown();
			for (Future<Booking> future : futures) {
				assertThat(future.get().status()).isEqualTo(BookingStatus.CONFIRMED);
			}
		} finally {
			pool.shutdownNow();
		}

		List<PaymentTransaction> txs = paymentTransactions.findByBookingId(pending.id());
		assertThat(txs).hasSize(1);
		assertThat(txs.get(0).type()).isEqualTo(PaymentTransactionType.PAYMENT);
		assertThat(txs.get(0).status()).isEqualTo(PaymentTransactionStatus.COMPLETED);
	}

	@Test
	void reusingIdempotencyKeyAcrossDifferentBookingsConflictsAndLeavesSecondPending() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID customerId = customer();
		String key = "reused-key-" + UUID.randomUUID();

		Booking first = bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()), now);
		assertThat(inventory.pay(customerId, first.id(), now, key)).isInstanceOf(PayOutcome.Paid.class);

		Booking second = bookings.book(customerId, showtimeId, List.of(hall.seats().get(1).id()), now);
		assertThat(inventory.pay(customerId, second.id(), now, key))
			.isEqualTo(new PayOutcome.IdempotencyKeyConflict());

		assertThat(bookingRepository.findById(second.id())).get()
			.extracting(Booking::status).isEqualTo(BookingStatus.PENDING);
		assertThat(paymentTransactions.findByBookingId(second.id())).isEmpty();
	}

	@Test
	void latePaymentAfterExpiryRecordsSingleAutoRefundWithoutBlockingLapsedHoldCleanup() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID seatId = hall.seats().get(0).id();
		UUID customerId = customer();
		Booking lapsed = bookings.book(customerId, showtimeId, List.of(seatId), now.minus(11, ChronoUnit.MINUTES));
		String key = "late-pay-" + UUID.randomUUID();

		assertThat(inventory.pay(customerId, lapsed.id(), now, key))
			.isEqualTo(new PayOutcome.NotPayable(BookingStatus.EXPIRED));
		assertThat(inventory.pay(customerId, lapsed.id(), now, key))
			.isEqualTo(new PayOutcome.NotPayable(BookingStatus.EXPIRED));

		List<PaymentTransaction> txs = paymentTransactions.findByBookingId(lapsed.id());
		assertThat(txs).hasSize(1);
		assertThat(txs.get(0).type()).isEqualTo(PaymentTransactionType.PAYMENT);
		assertThat(txs.get(0).status()).isEqualTo(PaymentTransactionStatus.REFUNDED);
		assertThat(txs.get(0).idempotencyKey()).isEqualTo(key);

		// ADR 0004: Another customer must still be able to claim the seat and hard-delete the lapsed booking.
		Booking nextCustomerBooking = bookings.book(customer(), showtimeId, List.of(seatId), now);
		assertThat(nextCustomerBooking.status()).isEqualTo(BookingStatus.PENDING);
		assertThat(bookingRepository.findById(lapsed.id())).isEmpty();
		// Even after hard-deletion of the lapsed booking, replaying the same key returns EXPIRED idempotently.
		assertThat(inventory.pay(customerId, lapsed.id(), now, key))
			.isEqualTo(new PayOutcome.NotPayable(BookingStatus.EXPIRED));
		assertThat(paymentTransactions.findByBookingId(lapsed.id())).hasSize(1);
	}

	@Test
	void showtimeCancellationRefundIsIdempotentWithAtMostOneRefundTransactionPerBooking() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID customerId = customer();
		Booking paid = bookings.book(customerId, showtimeId, List.of(hall.seats().get(0).id()), now);
		bookings.pay(customerId, paid.id(), now, "pay-before-cancel-" + UUID.randomUUID());

		showtimes.cancel(showtimeId, CancellationReason.EQUIPMENT_FAILURE, null, now);

		// Even if refund insertion is invoked again directly, DB partial unique index prevents duplicate refunds.
		transaction.executeWithoutResult(status -> jpa.insertRefundTransactionsForShowtime(showtimeId, now));

		List<PaymentTransaction> txs = paymentTransactions.findByBookingId(paid.id());
		assertThat(txs).hasSize(2);
		assertThat(txs).filteredOn(tx -> tx.type() == PaymentTransactionType.PAYMENT).hasSize(1);
		assertThat(txs).filteredOn(tx -> tx.type() == PaymentTransactionType.REFUND)
			.hasSize(1)
			.first()
			.satisfies(tx -> {
				assertThat(tx.status()).isEqualTo(PaymentTransactionStatus.REFUNDED);
				assertThat(tx.amount()).isEqualTo(paid.totalPrice());
				assertThat(tx.idempotencyKey()).isEqualTo("refund:" + paid.id());
			});
	}

	private UUID customer() {
		return accounts.registerCustomer("idemp-" + UUID.randomUUID() + "@example.com", "password123", "Customer").id();
	}

	private Auditorium auditorium() {
		UUID cinemaId = cinemas.create("Cinema " + UUID.randomUUID(), "Address").id();
		return auditoriums.create(cinemaId, "Hall", 2, 2, List.of(), List.of());
	}

	private UUID showtime(Auditorium hall) {
		UUID movieId = movies.create("Film", "Synopsis", 90, "Drama", AgeRating.P, LocalDate.parse("2026-09-01"),
			"poster").id();
		return showtimes.create(movieId, hall.id(), now.plus(1, ChronoUnit.DAYS), 100_000).id();
	}
}
