package com.clavaris.common.i18n;

import java.time.Instant;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * How a date reads in the dashboard, in the language of the person reading it. Every template that
 * shows a timestamp calls this (it used to be an English-only formatter), so one class decides how
 * a date looks in each supported language.
 *
 * <p>English: "April 15th, 2026" (ordinal day). Spanish: "15 de abril de 2026". Every instant is
 * shown in UTC: no per-user time zone preference exists yet, and the pages say "UTC" wherever a
 * time of day is shown.
 *
 * <p>Lives in {@code common}, not a business module, for the same reason the other shared value
 * helpers do: every module's own templates reach it. It has no Thymeleaf dependency; templates call
 * it through SpringEL's {@code T(...)} syntax.
 */
public final class LocalizedDateFormatter {

  private static final String ES_DATE = "d 'de' MMMM 'de' uuuu";
  private static final String ES_MONTH_YEAR = "MMMM 'de' uuuu";
  private static final String EN_DATE_TIME = "MMMM d, uuuu 'at' HH:mm 'UTC'";
  private static final String ES_DATE_TIME = "d 'de' MMMM 'de' uuuu, HH:mm 'UTC'";
  private static final int IRREGULAR_FROM = 11;
  private static final int IRREGULAR_TO = 13;
  private static final int DAYS_RADIX = 10;

  private LocalizedDateFormatter() {
    // Static helpers only.
  }

  /**
   * @return a long date in the current language, or {@code null} if {@code instant} is {@code null}
   *     (every call site already handles "blank when absent" with its own ternary or Elvis
   *     operator, and passing the {@code null} through keeps them all working).
   */
  public static String format(final Instant instant) {
    return format(instant, AppLocales.current());
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  public static String format(final Instant instant, final Locale locale) {
    if (instant == null) {
      return null;
    }
    final ZonedDateTime date = instant.atZone(ZoneOffset.UTC);
    if (AppLocales.isSpanish(locale)) {
      return DateTimeFormatter.ofPattern(ES_DATE, locale).format(date);
    }
    final int day = date.getDayOfMonth();
    return "%s %d%s, %d"
        .formatted(
            date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            day,
            ordinalSuffix(day),
            date.getYear());
  }

  /**
   * A date with the time of day: "October 3, 2026 at 09:00 UTC" / "3 de octubre de 2026, 09:00
   * UTC".
   */
  public static String dateTime(final Instant instant) {
    return dateTime(instant, AppLocales.current());
  }

  public static String dateTime(final Instant instant, final Locale locale) {
    final String pattern = AppLocales.isSpanish(locale) ? ES_DATE_TIME : EN_DATE_TIME;
    return DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(ZoneOffset.UTC));
  }

  /** "Oct" / "oct". */
  public static String monthShort(final Month month, final Locale locale) {
    return month.getDisplayName(TextStyle.SHORT, locale);
  }

  /** "October 2026" / "octubre de 2026". */
  public static String monthYear(final YearMonth month, final Locale locale) {
    final DateTimeFormatter formatter =
        AppLocales.isSpanish(locale)
            ? DateTimeFormatter.ofPattern(ES_MONTH_YEAR, locale)
            : DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);
    return formatter.format(month);
  }

  // 11th/12th/13th are the one irregular case (the last-digit rule would read
  // "11st"/"12nd"/"13rd").
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String ordinalSuffix(final int day) {
    if (day >= IRREGULAR_FROM && day <= IRREGULAR_TO) {
      return "th";
    }
    return switch (day % DAYS_RADIX) {
      case 1 -> "st";
      case 2 -> "nd";
      case 3 -> "rd";
      default -> "th";
    };
  }
}
