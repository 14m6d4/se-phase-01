package vn.edu.uet.cinema.persistence.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.edu.uet.cinema.business.account.Account;
import vn.edu.uet.cinema.business.account.AccountRepository;
import vn.edu.uet.cinema.business.account.EmailTakenException;
import vn.edu.uet.cinema.business.account.Role;

@Repository
class JpaAccountRepository implements AccountRepository {

	private static final String SELECT_BY_ID = """
		select id, email, password_hash, full_name, role from account where id = ?
		""";
	private static final String SELECT_BY_EMAIL = """
		select id, email, password_hash, full_name, role from account where email = ?
		""";
	private static final String EXISTS_BY_EMAIL = """
		select exists(select 1 from account where email = ?)
		""";

	private final AccountEntityRepository jpa;
	private final JdbcClient jdbc;

	JpaAccountRepository(AccountEntityRepository jpa, JdbcClient jdbc) {
		this.jpa = jpa;
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Account> findById(UUID id) {
		return jdbc.sql(SELECT_BY_ID).param(id).query(JpaAccountRepository::mapAccount).optional();
	}

	@Override
	public Optional<Account> findByEmail(String email) {
		return jdbc.sql(SELECT_BY_EMAIL).param(email).query(JpaAccountRepository::mapAccount).optional();
	}

	@Override
	public boolean existsByEmail(String email) {
		return Boolean.TRUE.equals(jdbc.sql(EXISTS_BY_EMAIL).param(email).query(Boolean.class).single());
	}

	@Override
	public Account save(Account account) {
		try {
			return jpa.saveAndFlush(AccountEntity.from(account)).toAccount();
		} catch (DataIntegrityViolationException e) {
			// Two registrations with the same email raced past existsByEmail; the unique constraint decides.
			throw new EmailTakenException();
		}
	}

	private static Account mapAccount(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new Account(
			rs.getObject("id", UUID.class),
			rs.getString("email"),
			rs.getString("password_hash"),
			rs.getString("full_name"),
			Role.valueOf(rs.getString("role")));
	}
}
