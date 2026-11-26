package vn.edu.uet.cinema.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MigrationIntegrityTest {

	private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V(\\d+)__.+\\.sql$");

	@Test
	void flywayMigrationsAreContiguousWithoutDuplicateVersions() throws Exception {
		Path dir = Path.of("src/main/resources/db/migration");
		List<Integer> versions;
		try (Stream<Path> files = Files.list(dir)) {
			versions = files.map(p -> p.getFileName().toString())
				.map(VERSIONED_MIGRATION::matcher)
				.filter(Matcher::matches)
				.map(m -> Integer.parseInt(m.group(1)))
				.sorted()
				.toList();
		}

		assertThat(versions).hasSize(14);
		assertThat(versions).doesNotHaveDuplicates();
		assertThat(versions).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
	}

	@Test
	void v12RefinesIndexesWithoutRedundantWriteOverhead() throws Exception {
		String sql = Files.readString(Path.of("src/main/resources/db/migration/V12__optimize_performance_indexes.sql"));

		assertThat(sql)
			.contains("DROP INDEX IF EXISTS ix_booked_seat_booking_id")
			.contains("DROP INDEX IF EXISTS ix_booking_pending_expires_at")
			.contains("CREATE INDEX ix_booking_customer_created_desc ON booking (customer_id, created_at DESC, id DESC)")
			.contains("CREATE INDEX ix_booking_showtime_status_expires ON booking (showtime_id, status, expires_at)")
			.contains("CREATE INDEX ix_showtime_scheduled_starts_at ON showtime (starts_at, id) WHERE status <> 'CANCELLED'");
	}

	@Test
	void v13CreatesPaymentTransactionTableWithIdempotencyConstraints() throws Exception {
		String sql = Files.readString(Path.of("src/main/resources/db/migration/V13__create_payment_transaction.sql"));

		assertThat(sql)
			.contains("CREATE TABLE payment_transaction")
			.contains("uq_payment_tx_booking_payment")
			.contains("uq_payment_tx_booking_refund")
			.contains("uq_payment_tx_customer_idempotency_key");
	}

	@Test
	void v14CreatesRefreshTokenTableWithHashUniquenessAndIndexes() throws Exception {
		String sql = Files.readString(Path.of("src/main/resources/db/migration/V14__create_refresh_token.sql"));

		assertThat(sql)
			.contains("CREATE TABLE refresh_token")
			.contains("fk_refresh_token_account")
			.contains("uq_refresh_token_hash")
			.contains("ix_refresh_token_account_id")
			.contains("ix_refresh_token_family_id")
			.contains("ix_refresh_token_expires_at");
	}
}
