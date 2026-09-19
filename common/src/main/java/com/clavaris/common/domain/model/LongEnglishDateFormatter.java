package com.clavaris.common.domain.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * SDE-III review, 2026-09-19: frontend-only date display change — every dashboard template that
 * renders a timestamp calls this instead of letting Thymeleaf fall back to {@link Instant#toString}
 * (raw ISO-8601), so every date shown reads as a long English date with an ordinal day suffix —
 * "April 15th, 2026" — never a time-of-day component. Backend/domain/persistence are untouched:
 * every stored value is still a plain {@link Instant} in UTC; this only changes what the HTML
 * shows.
 *
 * <p>Deliberately zero Spring/Thymeleaf dependency, same "utilities, common value types" role
 * {@link AbsoluteHttpsUrlValidator} already plays for this module — templates invoke it via
 * SpringEL's {@code T(com.clavaris.common.domain.model.LongEnglishDateFormatter).format(...)}
 * syntax, which needs no dialect registration. A custom Thymeleaf dialect (e.g. a {@code #longdate}
 * expression object) was deliberately rejected: dozens of existing controller tests across every
 * module construct their own bare {@code SpringTemplateEngine} with no custom dialects registered,
 * and a dialect-based approach would have broken every one of them.
 *
 * <p>Lives in {@code common}, not a single owning module, for the same reason {@link
 * AbsoluteHttpsUrlValidator} does — every module's own templates need to reach it, and no single
 * business module owns "how a date looks in the dashboard."
 */
public final class LongEnglishDateFormatter {

  private LongEnglishDateFormatter() {
    // utility class, never instantiated
  }

  /**
   * @param instant the timestamp to format, or {@code null}
   * @return a long English date ("April 15th, 2026"), always rendered in UTC (no per-user timezone
   *     preference exists in this system yet); {@code null} if {@code instant} is {@code null} —
   *     every call site already had its own "blank/fallback when absent" handling (a ternary, an
   *     {@code Optional}, an Elvis operator) before this formatter existed, and passing that {@code
   *     null} through unchanged keeps every one of them working exactly as before.
   */
  // Two exits (null short-circuit, plus the formatted-string return) — same "one exit per
  // distinct outcome" rationale as every other admin-API-adjacent method in this codebase with
  // the identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  public static String format(final Instant instant) {
    if (instant == null) {
      return null;
    }
    final ZonedDateTime date = instant.atZone(ZoneOffset.UTC);
    final int day = date.getDayOfMonth();
    return "%s %d%s, %d"
        .formatted(
            date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            day,
            ordinalSuffix(day),
            date.getYear());
  }

  // 11th/12th/13th are the one irregular case (would otherwise read "11st"/"12nd"/"13rd" off the
  // %10 rule below) — every other day's suffix follows the plain last-digit rule. Two exits, same
  // rationale as format's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String ordinalSuffix(final int day) {
    if (day >= 11 && day <= 13) {
      return "th";
    }
    return switch (day % 10) {
      case 1 -> "st";
      case 2 -> "nd";
      case 3 -> "rd";
      default -> "th";
    };
  }
}
