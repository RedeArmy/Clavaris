package com.clavaris.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

class LocalizedDateFormatterTest {

  @Test
  void formatsAPlainDay() {
    Instant instant = at(2026, Month.APRIL, 15);

    assertThat(LocalizedDateFormatter.format(instant)).isEqualTo("April 15th, 2026");
  }

  @Test
  void returnsNullForANullInstant() {
    assertThat(LocalizedDateFormatter.format(null)).isNull();
  }

  @Test
  void usesStSuffixFor1st21stAnd31st() {
    assertThat(LocalizedDateFormatter.format(at(2026, Month.JANUARY, 1)))
        .isEqualTo("January 1st, 2026");
    assertThat(LocalizedDateFormatter.format(at(2026, Month.JANUARY, 21)))
        .isEqualTo("January 21st, 2026");
    assertThat(LocalizedDateFormatter.format(at(2026, Month.JANUARY, 31)))
        .isEqualTo("January 31st, 2026");
  }

  @Test
  void usesNdSuffixFor2ndAnd22nd() {
    assertThat(LocalizedDateFormatter.format(at(2026, Month.FEBRUARY, 2)))
        .isEqualTo("February 2nd, 2026");
    assertThat(LocalizedDateFormatter.format(at(2026, Month.FEBRUARY, 22)))
        .isEqualTo("February 22nd, 2026");
  }

  @Test
  void usesRdSuffixFor3rdAnd23rd() {
    assertThat(LocalizedDateFormatter.format(at(2026, Month.MARCH, 3)))
        .isEqualTo("March 3rd, 2026");
    assertThat(LocalizedDateFormatter.format(at(2026, Month.MARCH, 23)))
        .isEqualTo("March 23rd, 2026");
  }

  // The one irregular case: 11th/12th/13th are "th", never "st"/"nd"/"rd", even though their last
  // digit (1/2/3) would otherwise suggest those suffixes.
  @Test
  void usesThSuffixFor11th12thAnd13thDespiteTheirLastDigit() {
    assertThat(LocalizedDateFormatter.format(at(2026, Month.MAY, 11))).isEqualTo("May 11th, 2026");
    assertThat(LocalizedDateFormatter.format(at(2026, Month.MAY, 12))).isEqualTo("May 12th, 2026");
    assertThat(LocalizedDateFormatter.format(at(2026, Month.MAY, 13))).isEqualTo("May 13th, 2026");
  }

  @Test
  void usesThSuffixForEveryOtherDay() {
    assertThat(LocalizedDateFormatter.format(at(2026, Month.JUNE, 4))).isEqualTo("June 4th, 2026");
    assertThat(LocalizedDateFormatter.format(at(2026, Month.JUNE, 20)))
        .isEqualTo("June 20th, 2026");
    assertThat(LocalizedDateFormatter.format(at(2026, Month.JUNE, 30)))
        .isEqualTo("June 30th, 2026");
  }

  @Test
  void dropsAnyTimeOfDayComponent() {
    Instant instant = ZonedDateTime.of(2026, 4, 15, 23, 59, 59, 0, ZoneOffset.UTC).toInstant();

    assertThat(LocalizedDateFormatter.format(instant)).isEqualTo("April 15th, 2026");
  }

  private static Instant at(final int year, final Month month, final int dayOfMonth) {
    return ZonedDateTime.of(year, month.getValue(), dayOfMonth, 12, 0, 0, 0, ZoneOffset.UTC)
        .toInstant();
  }

  // Spanish: day, "de", month in lower case, "de", year; no ordinal.
  @Test
  void formatsADateInSpanish() {
    assertThat(LocalizedDateFormatter.format(at(2026, Month.APRIL, 15), AppLocales.SPANISH))
        .isEqualTo("15 de abril de 2026");
    assertThat(LocalizedDateFormatter.format(at(2026, Month.JANUARY, 1), AppLocales.SPANISH))
        .isEqualTo("1 de enero de 2026");
  }

  @Test
  void formatsADateWithItsTimeInBothLanguages() {
    final Instant instant = ZonedDateTime.of(2026, 10, 3, 9, 5, 0, 0, ZoneOffset.UTC).toInstant();

    assertThat(LocalizedDateFormatter.dateTime(instant, AppLocales.ENGLISH))
        .isEqualTo("October 3, 2026 at 09:05 UTC");
    assertThat(LocalizedDateFormatter.dateTime(instant, AppLocales.SPANISH))
        .isEqualTo("3 de octubre de 2026, 09:05 UTC");
  }

  @Test
  void namesMonthsAndMonthYearsInBothLanguages() {
    assertThat(LocalizedDateFormatter.monthShort(Month.OCTOBER, AppLocales.ENGLISH))
        .isEqualTo("Oct");
    assertThat(LocalizedDateFormatter.monthShort(Month.OCTOBER, AppLocales.SPANISH))
        .startsWith("oct");
    assertThat(LocalizedDateFormatter.monthYear(YearMonth.of(2026, 10), AppLocales.ENGLISH))
        .isEqualTo("October 2026");
    assertThat(LocalizedDateFormatter.monthYear(YearMonth.of(2026, 10), AppLocales.SPANISH))
        .isEqualTo("octubre de 2026");
  }

  @Test
  void theCurrentLanguageDecidesWhenNoneIsGiven() {
    LocaleContextHolder.setLocale(AppLocales.SPANISH);
    try {
      assertThat(LocalizedDateFormatter.format(at(2026, Month.APRIL, 15)))
          .isEqualTo("15 de abril de 2026");
    } finally {
      LocaleContextHolder.resetLocaleContext();
    }
  }
}
