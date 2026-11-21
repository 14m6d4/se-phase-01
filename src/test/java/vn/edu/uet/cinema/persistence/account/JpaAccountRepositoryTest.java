package vn.edu.uet.cinema.persistence.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import vn.edu.uet.cinema.business.account.Account;
import vn.edu.uet.cinema.business.account.EmailTakenException;
import vn.edu.uet.cinema.business.account.Role;

class JpaAccountRepositoryTest {

	private final AccountEntityRepository jpa = mock(AccountEntityRepository.class);
	private final JpaAccountRepository repository = new JpaAccountRepository(jpa);
	private final Account account = new Account(UUID.randomUUID(), "user@cinema.local", "hash", "User", Role.CUSTOMER);

	@Test
	void throwsEmailTakenExceptionOnlyWhenAccountEmailKeyConstraintIsViolated() {
		var violation = new ConstraintViolationException("duplicate email",
			new SQLException("duplicate key", "23505"), "account_email_key");
		when(jpa.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("wrapped", violation));

		assertThatThrownBy(() -> repository.save(account))
			.isInstanceOf(EmailTakenException.class);
	}

	@Test
	void rethrowsDataIntegrityViolationExceptionForOtherConstraints() {
		var otherViolation = new ConstraintViolationException("check constraint",
			new SQLException("check failed", "23514"), "account_role_check");
		when(jpa.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("wrapped", otherViolation));

		assertThatThrownBy(() -> repository.save(account))
			.isInstanceOf(DataIntegrityViolationException.class)
			.isNotInstanceOf(EmailTakenException.class);
	}

	@Test
	void rethrowsGenericDataIntegrityViolationWithoutConstraintName() {
		when(jpa.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("generic error"));

		assertThatThrownBy(() -> repository.save(account))
			.isInstanceOf(DataIntegrityViolationException.class)
			.isNotInstanceOf(EmailTakenException.class);
	}

	@Test
	void successfullySavesAndReturnsAccount() {
		when(jpa.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Account saved = repository.save(account);
		assertThat(saved.id()).isEqualTo(account.id());
		assertThat(saved.email()).isEqualTo(account.email());
	}
}
