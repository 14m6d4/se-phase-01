package vn.edu.uet.cinema.business;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** One page of results plus total-count metadata, free of framework types (ADR 0001). */
public record PageSlice<T>(List<T> items, int page, int size, long totalElements) {

	public PageSlice {
		items = List.copyOf(Objects.requireNonNull(items, "items"));
		if (page < 0) {
			throw new IllegalArgumentException("Page must be 0 or greater");
		}
		if (size < 1) {
			throw new IllegalArgumentException("Size must be at least 1");
		}
		if (totalElements < 0) {
			throw new IllegalArgumentException("Total elements must be 0 or greater");
		}
	}

	public int totalPages() {
		return totalElements == 0 ? 0 : (int) Math.ceilDiv(totalElements, size);
	}

	public <R> PageSlice<R> map(Function<? super T, ? extends R> mapper) {
		return new PageSlice<>(items.stream().<R>map(mapper).toList(), page, size, totalElements);
	}
}
