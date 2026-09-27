package com.clavaris.common.domain.model;

/**
 * TD-PERF-020 (keyset revision, 2026-09-14): replaces the page-number {@code PageRequest} this
 * codebase's six dashboard lists originally shipped with — explicit request, converting all six to
 * a cursor shape matching {@code WebhookDeliveryRepository#claimDueBatch}'s own precedent (see
 * {@link KeysetCursor}'s own Javadoc for the exact seek-predicate columns this reuses).
 *
 * <p>Exactly one of {@code after}/{@code before} is set for a real "next"/"previous" navigation, or
 * neither for the first page — never both (this record's own compact constructor enforces it). A
 * page never needs to be requested by number: the two Thymeleaf pagination links this backs each
 * carry the opaque cursor of the row already on screen, not an integer a user could hand-edit into
 * an arbitrary offset.
 */
public record KeysetPageRequest(KeysetCursor after, KeysetCursor before, int size) {

  /** Dashboard default — same value as the page-number version this replaces. */
  public static final int DEFAULT_SIZE = 20;

  /** Hard ceiling — same rationale as the page-number version this replaces. */
  public static final int MAX_SIZE = 100;

  public KeysetPageRequest {
    if (after != null && before != null) {
      throw new IllegalArgumentException("Cannot page both after and before a cursor at once");
    }
    if (size < 1 || size > MAX_SIZE) {
      throw new IllegalArgumentException(
          "size must be between 1 and " + MAX_SIZE + ", was " + size);
    }
  }

  public static KeysetPageRequest first() {
    return new KeysetPageRequest(null, null, DEFAULT_SIZE);
  }

  public static KeysetPageRequest after(final KeysetCursor cursor) {
    return new KeysetPageRequest(cursor, null, DEFAULT_SIZE);
  }

  public static KeysetPageRequest before(final KeysetCursor cursor) {
    return new KeysetPageRequest(null, cursor, DEFAULT_SIZE);
  }

  /**
   * ADR-0029: the Users tab's own 25-per-page override — every other one of this codebase's
   * dashboard lists still gets {@link #DEFAULT_SIZE} through the no-size overloads above, unaware
   * this one exists.
   */
  public static KeysetPageRequest first(final int size) {
    return new KeysetPageRequest(null, null, size);
  }

  public static KeysetPageRequest after(final KeysetCursor cursor, final int size) {
    return new KeysetPageRequest(cursor, null, size);
  }

  public static KeysetPageRequest before(final KeysetCursor cursor, final int size) {
    return new KeysetPageRequest(null, cursor, size);
  }

  /**
   * Every one of this codebase's six paginated dashboard controllers builds a request from its own
   * GET's {@code ?after=}/{@code ?before=} query params the exact same way — genuinely shared
   * decode logic, not the structural-mirroring kind a same-named helper backfired on earlier this
   * session (see {@code PlatformOAuthClientController}'s own commit history): this one lives once,
   * here, not copied six times. A blank/absent param is treated as "not given," matching Spring's
   * own {@code @RequestParam(required = false)} default. Malformed input still decodes loudly (see
   * {@link KeysetCursor}'s own Javadoc) — never silently falls back to the first page.
   */
  public static KeysetPageRequest fromCursors(final String after, final String before) {
    return fromCursors(after, before, DEFAULT_SIZE);
  }

  /** ADR-0029: same decode logic as the two-arg overload, with an explicit page size. */
  @SuppressWarnings("PMD.OnlyOneReturn") // three real, distinct exits — same rationale as every
  // other short-circuiting lookup in this codebase.
  public static KeysetPageRequest fromCursors(
      final String after, final String before, final int size) {
    if (after != null && !after.isBlank()) {
      return after(KeysetCursor.decode(after), size);
    }
    if (before != null && !before.isBlank()) {
      return before(KeysetCursor.decode(before), size);
    }
    return first(size);
  }

  public boolean isFirst() {
    return after == null && before == null;
  }
}
