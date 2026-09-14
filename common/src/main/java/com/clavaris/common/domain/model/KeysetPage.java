package com.clavaris.common.domain.model;

import java.util.List;

/**
 * TD-PERF-020 (keyset revision, 2026-09-14): the result half of {@link KeysetPageRequest} —
 * replaces the page-number {@code Page<T>} this codebase's six dashboard lists originally shipped
 * with. Deliberately carries no {@code totalElements}/{@code totalPages()} — computing either means
 * a real {@code COUNT(*)}, exactly the query keyset pagination exists to avoid; a dashboard
 * template rendering this shows Previous/Next controls only, never a "Page X of Y" count.
 *
 * <p>{@code startCursor}/{@code endCursor} are the first/last row's own {@link KeysetCursor} on
 * this page (null when {@code content} is empty) — a template turns these into the {@code ?before=}
 * / {@code ?after=} query parameter of its own Previous/Next links, never a raw field of the
 * underlying row.
 */
public record KeysetPage<T>(
    List<T> content,
    KeysetCursor startCursor,
    KeysetCursor endCursor,
    boolean hasNext,
    boolean hasPrevious) {

  public KeysetPage {
    content = List.copyOf(content);
  }

  public boolean isEmpty() {
    return content.isEmpty();
  }
}
