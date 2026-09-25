package vn.edu.uet.cinema.business.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.uet.cinema.business.ConflictException;

class AccountServiceTest {

	private final Map<UUID, Account> store = new HashMap<>();
	private final AccountService service = new AccountService(new InMemoryAccounts(), new PrefixHasher());

	@Test
	void registersCustomerWithNormalizedEmailAndHashedPassword() {
		Account account = service.registerCustomer("  Alice@Example.COM ", "secret123", " Alice ");

		assertThat(account.email()).isEqualTo("alice@example.com");
		assertThat(account.fullName()).isEqualTo("Alice");
		assertThat(account.role()).isEqualTo(Role.CUSTOMER);
		assertThat(account.passwordHash()).isEqualTo("hashed:secret123");
	}

	@Test
	void rejectsDuplicateEmailRegardlessOfCase() {
		service.registerCustomer("alice@example.com", "secret123", "Alice");

		assertThatThrownBy(() -> service.registerCustomer("ALICE@example.com", "other1234", "Alice 2"))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void authenticatesWithCorrectPasswordOnly() {
		Account registered = service.registerCustomer("alice@example.com", "secret123", "Alice");

		assertThat(service.authenticate("Alice@example.com", "secret123")).isEqualTo(registered);
		assertThatThrownBy(() -> service.authenticate("alice@example.com", "wrong"))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> service.authenticate("nobody@example.com", "secret123"))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void ensureAdminCreatesTheAdminOnce() {
		service.ensureAdmin("Admin@Cinema.local", "admin-pass");
		service.ensureAdmin("admin@cinema.local", "changed-pass");

		assertThat(store).hasSize(1);
		assertThat(service.authenticate("admin@cinema.local", "admin-pass").role()).isEqualTo(Role.ADMIN);
	}

	@Test
	void unknownEmailStillCostsAHashComparison() {
		CountingHasher hasher = new CountingHasher();
		AccountService counted = new AccountService(new InMemoryAccounts(), hasher);

		assertThatThrownBy(() -> counted.authenticate("nobody@example.com", "secret123"))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThat(hasher.matchCalls).isEqualTo(1);
	}

	private class InMemoryAccounts implements AccountRepository {

		public Optional<Account> findById(UUID id) {
			return Optional.ofNullable(store.get(id));
		}

		public Optional<Account> findByEmail(String email) {
			return store.values().stream().filter(a -> a.email().equals(email)).findFirst();
		}

		public boolean existsByEmail(String email) {
			return findByEmail(email).isPresent();
		}

		public Account save(Account account) {
			store.put(account.id(), account);
			return account;
		}
	}

	private static class PrefixHasher implements PasswordHasher {

		public String hash(String rawPassword) {
			return "hashed:" + rawPassword;
		}

		public boolean matches(String rawPassword, String hash) {
			return hash.equals(hash(rawPassword));
		}
	}

	private static class CountingHasher extends PrefixHasher {

		int matchCalls;

		@Override
		public boolean matches(String rawPassword, String hash) {
			matchCalls++;
			return super.matches(rawPassword, hash);
		}
	}
}
