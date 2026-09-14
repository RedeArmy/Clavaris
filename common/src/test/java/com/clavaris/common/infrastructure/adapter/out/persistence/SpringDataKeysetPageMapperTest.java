package com.clavaris.common.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpringDataKeysetPageMapperTest {

  // A stand-in "entity" — real callers map a JPA @Entity here, but the mapper itself is entity-type
  // agnostic; a plain record with the two seek-predicate columns is enough to exercise it.
  private record Row(UUID id, Instant createdAt, String value) {}

  private static Row row(final int secondsAgo, final String value) {
    return new Row(UUID.randomUUID(), Instant.now().minus(secondsAgo, ChronoUnit.SECONDS), value);
  }

  private static KeysetCursor cursorOf(final Row row) {
    return new KeysetCursor(row.createdAt(), row.id());
  }

  @Test
  void forwardTrimsTheExtraRowAndReportsHasNextWhenOneExists() {
    // newest-first: index 0 is "the extra row" fetched only to prove hasNext.
    final List<Row> fetchedDescendingPlusOne =
        List.of(row(0, "c"), row(1, "b"), row(2, "a")); // size=2 requested, 3 fetched

    final KeysetPage<String> page =
        SpringDataKeysetPageMapper.forward(
            fetchedDescendingPlusOne,
            2,
            false,
            Row::value,
            SpringDataKeysetPageMapperTest::cursorOf);

    assertThat(page.content()).containsExactly("c", "b");
    assertThat(page.hasNext()).isTrue();
    assertThat(page.hasPrevious()).isFalse();
  }

  @Test
  void forwardReportsNoHasNextWhenFetchDidNotExceedSize() {
    final List<Row> fetchedDescending = List.of(row(0, "b"), row(1, "a"));

    final KeysetPage<String> page =
        SpringDataKeysetPageMapper.forward(
            fetchedDescending, 2, true, Row::value, SpringDataKeysetPageMapperTest::cursorOf);

    assertThat(page.content()).containsExactly("b", "a");
    assertThat(page.hasNext()).isFalse();
    assertThat(page.hasPrevious()).isTrue();
  }

  @Test
  void forwardOnAnEmptyFetchProducesNullCursors() {
    final KeysetPage<String> page =
        SpringDataKeysetPageMapper.forward(
            List.of(), 2, true, Row::value, SpringDataKeysetPageMapperTest::cursorOf);

    assertThat(page.isEmpty()).isTrue();
    assertThat(page.startCursor()).isNull();
    assertThat(page.endCursor()).isNull();
    assertThat(page.hasNext()).isFalse();
  }

  @Test
  void backwardReversesTheAscendingFetchBackToNewestFirst() {
    // Ascending fetch ("WHERE col > cursor ORDER BY col ASC LIMIT size+1") lists the rows nearest
    // the cursor FIRST (smallest excess above it) — so with size=2, "e" and "d" are the two real
    // rows to keep and "c" (the farthest/newest of the three) is the trimmed-off extra, proving
    // hasPrevious. Reversing the kept two back to newest-first gives [d, e].
    final List<Row> fetchedAscendingPlusOne = List.of(row(4, "e"), row(3, "d"), row(2, "c"));

    final KeysetPage<String> page =
        SpringDataKeysetPageMapper.backward(
            fetchedAscendingPlusOne, 2, Row::value, SpringDataKeysetPageMapperTest::cursorOf);

    assertThat(page.content()).containsExactly("d", "e");
    assertThat(page.hasPrevious()).isTrue();
    assertThat(page.hasNext())
        .isTrue(); // unconditional — arriving via a cursor proves a later page
  }

  @Test
  void backwardReportsNoHasPreviousWhenFetchDidNotExceedSize() {
    final List<Row> fetchedAscending = List.of(row(3, "d"), row(2, "c"));

    final KeysetPage<String> page =
        SpringDataKeysetPageMapper.backward(
            fetchedAscending, 2, Row::value, SpringDataKeysetPageMapperTest::cursorOf);

    assertThat(page.content()).containsExactly("c", "d");
    assertThat(page.hasPrevious()).isFalse();
    assertThat(page.hasNext()).isTrue();
  }
}
