package vn.edu.uet.cinema.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import vn.edu.uet.cinema.business.PageSlice;

/** Formats paginated JSON-array responses with standard pagination metadata headers. */
public final class PaginationHeaders {

	public static final String TOTAL_COUNT = "X-Total-Count";
	public static final String PAGE = "X-Page";
	public static final String PAGE_SIZE = "X-Page-Size";
	public static final String TOTAL_PAGES = "X-Total-Pages";

	private PaginationHeaders() {
	}

	public static <T, R> ResponseEntity<List<R>> ok(PageSlice<T> slice, Function<? super T, ? extends R> mapper) {
		List<R> body = slice.items().stream().<R>map(mapper).toList();
		return ResponseEntity.ok()
			.header(TOTAL_COUNT, Long.toString(slice.totalElements()))
			.header(PAGE, Integer.toString(slice.page()))
			.header(PAGE_SIZE, Integer.toString(slice.size()))
			.header(TOTAL_PAGES, Integer.toString(slice.totalPages()))
			.body(body);
	}
}
