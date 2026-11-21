package vn.edu.uet.cinema.business;

/** Zero-based pagination parameters with safe bounds. */
public record PageQuery(int page, int size) {

	public static final int DEFAULT_PAGE = 0;
	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;
	public static final int MAX_PAGE = 100_000;
	public static final PageQuery DEFAULT = new PageQuery(DEFAULT_PAGE, DEFAULT_SIZE);

	public PageQuery {
		if (page < 0 || page > MAX_PAGE) {
			throw new IllegalArgumentException("Page must be between 0 and " + MAX_PAGE);
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new IllegalArgumentException("Size must be between 1 and " + MAX_SIZE);
		}
	}

	public int offset() {
		return page * size;
	}
}
