package vn.edu.uet.cinema.business.account;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

	String EMAIL_TAKEN = "Email already registered";

	Optional<Account> findById(UUID id);

	Optional<Account> findByEmail(String email);

	boolean existsByEmail(String email);

	/** @throws vn.edu.uet.cinema.business.ConflictException if the email is already taken */
	Account save(Account account);
}
