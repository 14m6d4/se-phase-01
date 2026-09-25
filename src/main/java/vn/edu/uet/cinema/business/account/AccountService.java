package vn.edu.uet.cinema.business.account;

import java.util.Locale;
import java.util.UUID;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;

public class AccountService {

	private final AccountRepository accounts;
	private final PasswordHasher passwordHasher;

	public AccountService(AccountRepository accounts, PasswordHasher passwordHasher) {
		this.accounts = accounts;
		this.passwordHasher = passwordHasher;
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

	// ponytail: an unknown email returns faster than a wrong password (no hash check), which leaks
	// which emails exist; hash a dummy password here if account enumeration matters.
	public Account authenticate(String email, String password) {
		return accounts.findByEmail(normalize(email))
			.filter(account -> passwordHasher.matches(password, account.passwordHash()))
			.orElseThrow(InvalidCredentialsException::new);
	}

	public Account get(UUID id) {
		return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account not found"));
	}

	private static String normalize(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}
}
