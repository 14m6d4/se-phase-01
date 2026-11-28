package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

	@Test
	void nullRequestReturnsNull() {
		assertThat(ClientIpResolver.resolve(null)).isNull();
	}

	@Test
	void ignoresUntrustedXForwardedForAndUsesRemoteAddr() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(" 198.51.100.42 ");
		request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");

		assertThat(ClientIpResolver.resolve(request)).isEqualTo("198.51.100.42");
	}

	@Test
	void blankRemoteAddrFallsBackToUnknown() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("   ");

		assertThat(ClientIpResolver.resolve(request)).isEqualTo("unknown");
	}
}
