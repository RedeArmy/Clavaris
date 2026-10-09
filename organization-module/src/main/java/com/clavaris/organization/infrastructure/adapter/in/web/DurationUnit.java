package com.clavaris.organization.infrastructure.adapter.in.web;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * The units a person can state a duration in on the dashboard, and how many minutes each is worth.
 * Everything below the web layer still speaks minutes (the domain, the REST API and the database
 * are unchanged); this exists so a form can say "18 hours" instead of "1080".
 *
 * <p>A month is 30 days and a year 365, the same lengths the domain's own bounds use (one year is
 * {@code 525,600} minutes), so a bound stated in years round-trips exactly.
 */
public enum DurationUnit {
  MINUTES(1, "Minutes"),
  HOURS(60, "Hours"),
  DAYS(1_440, "Days"),
  WEEKS(10_080, "Weeks"),
  MONTHS(43_200, "Months"),
  YEARS(525_600, "Years");

  private final int minutesPerUnit;
  private final String text;

  DurationUnit(final int minutesPerUnit, final String label) {
    this.minutesPerUnit = minutesPerUnit;
    this.text = label;
  }

  /** How many minutes one of this unit is. */
  public int minutes() {
    return minutesPerUnit;
  }

  /** The plural name shown in the unit selector. */
  public String label() {
    return text;
  }

  /** The length of {@code amount} of this unit, in minutes. */
  public long toMinutes(final long amount) {
    return amount * minutesPerUnit;
  }

  /** The unit named by {@code code} (any case), if there is one. */
  public static Optional<DurationUnit> parse(final String code) {
    return code == null
        ? Optional.empty()
        : Arrays.stream(values())
            .filter(unit -> unit.name().equals(code.strip().toUpperCase(Locale.ROOT)))
            .findFirst();
  }

  /**
   * The largest unit that states {@code minutes} exactly, so a stored 1080 reads "18 hours" and
   * 10,080 reads "1 week" rather than the raw count.
   */
  public static Amount bestFit(final int minutes) {
    final DurationUnit unit =
        Arrays.stream(values())
            .sorted(Comparator.comparingInt(DurationUnit::minutes).reversed())
            .filter(candidate -> minutes % candidate.minutes() == 0)
            .findFirst()
            .orElse(MINUTES);
    return new Amount(minutes / unit.minutes(), unit);
  }

  /** {@code minutes} in words, in the largest exact unit: "5 minutes", "1 year", "18 hours". */
  public static String describe(final int minutes) {
    final Amount amount = bestFit(minutes);
    final String unit = amount.unit().label().toLowerCase(Locale.ROOT);
    return amount.value() == 1
        ? amount.value() + " " + unit.substring(0, unit.length() - 1)
        : amount.value() + " " + unit;
  }

  /** A whole number of one unit, such as 18 hours. */
  public record Amount(int value, DurationUnit unit) {}
}
