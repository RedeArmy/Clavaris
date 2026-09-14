package com.clavaris.common.infrastructure.adapter.out.persistence;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * TD-PERF-020 (keyset revision, 2026-09-14): the one place every JPA adapter backing a keyset-paged
 * dashboard list turns a raw, "one row over the requested size" fetch into a {@link KeysetPage} —
 * extracted immediately (not after a second/third copy, unlike {@code SpringDataPageMapper}'s own
 * history) since the exact "fetch {@code size + 1}, trim, and derive {@code hasNext}/{@code
 * hasPrevious} from whether that extra row existed" logic is identical across all six converted
 * repositories from the very first one, and reversing a "before" page's own ascending fetch back to
 * this codebase's newest-first display order is easy to get backwards once, let alone six times.
 *
 * <p>Each repository still owns its own {@code @Query} — the seek predicate's scoping column
 * (`organizationId`, `workspaceId`, `ownerPlatformAccountId`, ...) and entity type genuinely differ
 * per list; only the page-shaping logic below is shared.
 */
// PMD.LongVariable: fetchedDescendingPlusOne/fetchedAscendingPlusOne name exactly what each
// parameter is (both the ordering direction and the "+1 row for hasNext/hasPrevious" contract) —
// same "deliberate, descriptive name over an arbitrary shortening" convention this codebase
// applies everywhere else this rule fires.
@SuppressWarnings("PMD.LongVariable")
public final class SpringDataKeysetPageMapper {

  private SpringDataKeysetPageMapper() {}

  /**
   * The first page (no cursor) or a "next" page (after a cursor) — both fetched in this codebase's
   * own newest-first order, so the raw list needs no reordering before it becomes {@code content}.
   *
   * @param fetchedDescendingPlusOne at most {@code size + 1} rows, newest-first; the {@code + 1}th
   *     row (if present) is trimmed off and exists only to answer {@code hasNext}.
   * @param hasPrevious {@code false} for the first page, {@code true} for an "after" page — known
   *     unconditionally by the caller (arriving via a real cursor already proves an earlier page
   *     exists), never derived from the fetch itself.
   */
  public static <E, T> KeysetPage<T> forward(
      final List<E> fetchedDescendingPlusOne,
      final int size,
      final boolean hasPrevious,
      final Function<E, T> toDomain,
      final Function<E, KeysetCursor> cursorOf) {
    final boolean hasNext = fetchedDescendingPlusOne.size() > size;
    final List<E> page =
        hasNext ? fetchedDescendingPlusOne.subList(0, size) : fetchedDescendingPlusOne;
    return toKeysetPage(page, hasNext, hasPrevious, toDomain, cursorOf);
  }

  /**
   * A "previous" page (before a cursor) — fetched in ascending order (the only way to seek backward
   * from a cursor with a {@code LIMIT}), so the trimmed page is reversed back to newest-first
   * before becoming {@code content}. {@code hasNext} is unconditionally {@code true}: arriving here
   * via a real cursor already proves a later page exists.
   *
   * @param fetchedAscendingPlusOne at most {@code size + 1} rows, oldest-first; the {@code + 1}th
   *     row (if present) is trimmed off and exists only to answer {@code hasPrevious}.
   */
  public static <E, T> KeysetPage<T> backward(
      final List<E> fetchedAscendingPlusOne,
      final int size,
      final Function<E, T> toDomain,
      final Function<E, KeysetCursor> cursorOf) {
    final boolean hasPrevious = fetchedAscendingPlusOne.size() > size;
    final List<E> trimmed =
        hasPrevious ? fetchedAscendingPlusOne.subList(0, size) : fetchedAscendingPlusOne;
    final List<E> page = new ArrayList<>(trimmed);
    Collections.reverse(page);
    return toKeysetPage(page, true, hasPrevious, toDomain, cursorOf);
  }

  private static <E, T> KeysetPage<T> toKeysetPage(
      final List<E> page,
      final boolean hasNext,
      final boolean hasPrevious,
      final Function<E, T> toDomain,
      final Function<E, KeysetCursor> cursorOf) {
    final List<T> content = page.stream().map(toDomain).toList();
    final KeysetCursor startCursor = page.isEmpty() ? null : cursorOf.apply(page.get(0));
    final KeysetCursor endCursor =
        page.isEmpty() ? null : cursorOf.apply(page.get(page.size() - 1));
    return new KeysetPage<>(content, startCursor, endCursor, hasNext, hasPrevious);
  }
}
