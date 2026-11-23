package vn.edu.uet.cinema.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageQueryTest {

	@Test
	void validBoundsAndOffsetAreComputed() {
		assertThat(PageQuery.DEFAULT).isEqualTo(new PageQuery(0, 20));
		assertThat(new PageQuery(0, 1).offset()).isZero();
		assertThat(new PageQuery(3, 25).offset()).isEqualTo(75);
		assertThat(new PageQuery(PageQuery.MAX_PAGE, PageQuery.MAX_SIZE).offset()).isEqualTo(10_000_000);
	}

	@Test
	void invalidBoundsAreRejected() {
		assertThatThrownBy(() -> new PageQuery(-1, 20)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PageQuery(PageQuery.MAX_PAGE + 1, 20)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PageQuery(0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PageQuery(0, PageQuery.MAX_SIZE + 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void pageSliceComputesTotalPagesMapsItemsAndCopiesDefensively() {
		List<Integer> mutable = new ArrayList<>(List.of(1, 2));
		PageSlice<Integer> slice = new PageSlice<>(mutable, 0, 20, 21);
		mutable.add(3);

		assertThat(slice.items()).containsExactly(1, 2);
		assertThat(slice.totalPages()).isEqualTo(2);
		assertThat(slice.map(Object::toString)).isEqualTo(new PageSlice<>(List.of("1", "2"), 0, 20, 21));
		assertThat(new PageSlice<>(List.of(), 0, 20, 0).totalPages()).isZero();
		assertThat(new PageSlice<>(List.of(1), 0, 20, 20).totalPages()).isEqualTo(1);
	}
}
