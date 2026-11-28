package vn.edu.uet.cinema.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client IP address solely from {@link HttpServletRequest#getRemoteAddr()} so untrusted
 * {@code X-Forwarded-For} headers cannot spoof rate limits or anti-hoarding quotas. Behind a trusted
 * reverse proxy, configure {@code server.forward-headers-strategy} (e.g. {@code native}) with trusted
 * proxy ranges so the servlet container updates {@code remoteAddr} before filters or controllers run.
 */
public final class ClientIpResolver {

	private ClientIpResolver() {
	}

	/**
	 * Returns the trimmed remote address from {@code request}, {@code "unknown"} if blank, or
	 * {@code null} when {@code request} itself is {@code null}.
	 */
	public static String resolve(HttpServletRequest request) {
		if (request == null) {
			return null;
		}
		String remoteAddr = request.getRemoteAddr();
		if (remoteAddr == null || remoteAddr.isBlank()) {
			return "unknown";
		}
		return remoteAddr.strip();
	}
}
