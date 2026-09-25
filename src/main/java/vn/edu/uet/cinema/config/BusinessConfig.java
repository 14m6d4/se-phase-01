package vn.edu.uet.cinema.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.edu.uet.cinema.business.account.AccountRepository;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.account.PasswordHasher;

/** Business services carry no Spring annotations (ADR 0001), so they are wired here. */
@Configuration
class BusinessConfig {

	@Bean
	AccountService accountService(AccountRepository accounts, PasswordHasher passwordHasher) {
		return new AccountService(accounts, passwordHasher);
	}

	/** Admins are never self-registered: the one Admin comes from ADMIN_EMAIL / ADMIN_PASSWORD. */
	@Bean
	ApplicationRunner adminBootstrap(AccountService accounts, @Value("${app.admin.email}") String email,
		@Value("${app.admin.password}") String password) {
		return args -> accounts.ensureAdmin(email, password);
	}
}
