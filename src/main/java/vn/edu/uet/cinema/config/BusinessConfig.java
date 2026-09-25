package vn.edu.uet.cinema.config;

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
}
