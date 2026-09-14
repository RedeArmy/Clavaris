package com.clavaris.common.domain.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * TD-PERF-020 (keyset revision, 2026-09-14): a stable position in one of this codebase's own
 * newest-first (`createdAt` descending, `id` descending as a tiebreaker) dashboard lists — the same
 * two-column sort key every one of those six lists already used for page-number pagination's own
 * determinism, now the actual seek predicate instead of just an {@code ORDER BY}.
 *
 * <p>{@code createdAt} is kept at full precision (encoded via {@link Instant#toString()}, not
 * {@link Instant#toEpochMilli()}) — Postgres {@code timestamptz} stores microsecond precision, and
 * truncating a cursor to millisecond precision could silently skip or repeat a row that shares a
 * millisecond with its neighbor. {@code id} alone breaks the remaining tie deterministically, same
 * as every one of these lists' own {@code ORDER BY} already relied on.
 *
 * <p>{@link #encode()}/{@link #decode(String)} produce/consume one opaque, URL-safe token — callers
 * never construct or parse a cursor's own fields directly, so its internal shape is free to change
 * later without breaking a bookmarked/shared {@code ?after=}/{@code ?before=} URL's own contract
 * (only that it round-trips through this class). A malformed/tampered token is not caught here or
 * by any caller — same "a hand-typed/manipulated query param is not a real user flow, a hard
 * failure is acceptable" posture this codebase's own page-number {@code PageRequest} already
 * documented for an equivalent case; this class simply lets {@link IllegalArgumentException}/{@link
 * java.time.format.DateTimeParseException} propagate.
 */
@SuppressWarnings("PMD.ShortVariable") // id — same name every domain entity in this codebase
// exposes its own identity through (Organization#id, Workspace#id, ...); this cursor just carries
// the same one, not a fresh name for it.
public record KeysetCursor(Instant createdAt, UUID id) {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private static final char FIELD_SEPARATOR = '|';

  public String encode() {
    final String raw = createdAt.toString() + FIELD_SEPARATOR + id;
    return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static KeysetCursor decode(final String token) {
    final String raw = new String(DECODER.decode(token), StandardCharsets.UTF_8);
    final int separatorIndex = raw.indexOf(FIELD_SEPARATOR);
    if (separatorIndex < 0) {
      throw new IllegalArgumentException("Malformed keyset cursor token");
    }
    return new KeysetCursor(
        Instant.parse(raw.substring(0, separatorIndex)),
        UUID.fromString(raw.substring(separatorIndex + 1)));
  }
}
