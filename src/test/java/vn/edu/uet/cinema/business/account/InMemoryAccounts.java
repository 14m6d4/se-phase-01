package vn.edu.uet.cinema.business.account;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-memory {@link AccountRepository} for business tests. */
public class InMemoryAccounts implements AccountRepository {

	private final Map<UUID, Account> rows = new HashMap<>();

	public int size() {
		return rows.size();
	}

	@Override
	public Optional<Account> findById(UUID id) {
		return Optional.ofNullable(rows.get(id));
	}

	@Override
	public Optional<Account> findByEmail(String email) {
		if (email == null) {
			return Optional.empty();
		}
		return rows.values().stream().filter(a -> a.email().equalsIgnoreCase(email)).findFirst();
	}

	@Override
	public boolean existsByEmail(String email) {
		return findByEmail(email).isPresent();
	}

	@Override
	public Account save(Account account) {
		Objects.requireNonNull(account, "account must not be null");
		Objects.requireNonNull(account.id(), "account id must not be null");
		Objects.requireNonNull(account.email(), "account email must not be null");
		Objects.requireNonNull(account.passwordHash(), "account passwordHash must not be null");
		Objects.requireNonNull(account.fullName(), "account fullName must not be null");
		Objects.requireNonNull(account.role(), "account role must not be null");

		for (Account existing : rows.values()) {
			if (existing.email().equalsIgnoreCase(account.email()) && !existing.id().equals(account.id())) {
				throw new EmailTakenException();
			}
		}
		rows.put(account.id(), account);
		return account;
	}
}
