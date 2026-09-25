package vn.edu.uet.cinema.business.account;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;

public class AccountService {

	private final AccountRepository accounts;
	private final PasswordHasher passwordHasher;
	/** Checked against when the email is unknown, so both failure paths cost one hash comparison. */
	private final String dummyHash;

	public AccountService(AccountRepository accounts, PasswordHasher passwordHasher) {
		this.accounts = accounts;
		this.passwordHasher = passwordHasher;
		this.dummyHash = passwordHasher.hash("not-a-real-password");
	}

	/** Self-registration always creates a Customer; Admins are seeded, never registered. */
	public Account registerCustomer(String email, String password, String fullName) {
		String normalizedEmail = normalize(email);
		if (accounts.existsByEmail(normalizedEmail)) {
			throw new ConflictException("Email already registered");
		}
		return accounts.save(new Account(UUID.randomUUID(), normalizedEmail, passwordHasher.hash(password),
			fullName.strip(), Role.CUSTOMER));
	}

	public Account authenticate(String email, String password) {
		Optional<Account> account = accounts.findByEmail(normalize(email));
		// Always hash-compare so an unknown email takes as long as a wrong password (no account enumeration by timing).
		boolean passwordMatches = passwordHasher.matches(password,
			account.map(Account::passwordHash).orElse(dummyHash));
		if (account.isEmpty() || !passwordMatches) {
			throw new InvalidCredentialsException();
		}
		return account.get();
	}

	public Account get(UUID id) {
		return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account not found"));
	}

	private static String normalize(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}
}
