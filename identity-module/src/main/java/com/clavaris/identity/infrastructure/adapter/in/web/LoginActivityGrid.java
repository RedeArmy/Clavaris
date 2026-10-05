package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.i18n.AppLocales;
import com.clavaris.common.i18n.LocalizedDateFormatter;
import com.clavaris.identity.application.usecase.getloginactivityforaccount.GetLoginActivityForAccountService;
import com.clavaris.identity.application.usecase.getloginactivityforaccount.LoginActivityDay;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * TD-FUT-034, Clerk "View Profile" activity heatmap parity: turns the sparse day-count list {@code
 * GetLoginActivityForAccountUseCase} returns into what the Activity card shows: a dense,
 * Sunday-aligned calendar grid (the convention GitHub's contribution graph uses), month labels, the
 * headline totals and a per-month table. Purely a rendering concern, so it lives here rather than
 * in the use case, and it takes {@code today} as a parameter so it is testable without a clock.
 *
 * <p>Days are UTC calendar days, the same bucketing the database query applies. Every figure
 * (totals, active days, months) is counted from the cells actually drawn, so the numbers always
 * agree with the picture. Grid padding cells (outside the trailing window, needed only to complete
 * a partial first or last week) carry a null date and level 0.
 */
final class LoginActivityGrid {

  private static final int DAYS_PER_WEEK = 7;
  // A month label needs about two columns of room; closer than this and the earlier one yields.
  private static final int MIN_LABEL_SPACING = 3;
  // Fixed thresholds, a few named buckets rather than a continuous scale, as GitHub does.
  private static final long SINGLE = 1;
  private static final long LEVEL_1_MAX = 1;
  private static final long LEVEL_2_MAX = 3;
  private static final long LEVEL_3_MAX = 6;

  private LoginActivityGrid() {
    // Static helpers only.
  }

  /** The current UTC day, the one every figure here is measured against. */
  /* default */ static LocalDate todayUtc() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /* default */ static LoginActivityView build(
      final List<LoginActivityDay> activity, final LocalDate today) {
    final Map<LocalDate, Long> counts =
        activity.stream()
            .collect(Collectors.toMap(LoginActivityDay::date, LoginActivityDay::count, Long::sum));
    final LocalDate windowStart =
        today.minusDays(GetLoginActivityForAccountService.WINDOW_DAYS - 1L);
    final List<List<HeatmapDayCell>> weeks = weeks(counts, windowStart, today);

    // Every figure is counted from the cells drawn, so the numbers always agree with the picture.
    final List<HeatmapDayCell> days =
        weeks.stream().flatMap(List::stream).filter(cell -> cell.date() != null).toList();
    return new LoginActivityView(
        weeks,
        monthLabels(weeks),
        days.stream().mapToLong(HeatmapDayCell::count).sum(),
        days.stream().filter(cell -> cell.count() > 0).count(),
        longestStreak(days),
        monthTotals(days));
  }

  // The longest run of consecutive days that each have at least one sign-in. The cells arrive in
  // date order, so a run is just a counter that resets on every empty day.
  private static long longestStreak(final List<HeatmapDayCell> days) {
    long longest = 0;
    long current = 0;
    for (final HeatmapDayCell day : days) {
      current = day.count() > 0 ? current + 1 : 0;
      longest = Math.max(longest, current);
    }
    return longest;
  }

  private static List<List<HeatmapDayCell>> weeks(
      final Map<LocalDate, Long> counts, final LocalDate windowStart, final LocalDate today) {
    final LocalDate gridStart =
        windowStart.minusDays(windowStart.getDayOfWeek().getValue() % DAYS_PER_WEEK);
    final List<List<HeatmapDayCell>> weeks = new ArrayList<>();
    List<HeatmapDayCell> week = new ArrayList<>();
    for (LocalDate cursor = gridStart; !cursor.isAfter(today); cursor = cursor.plusDays(1)) {
      final boolean inWindow = !cursor.isBefore(windowStart);
      week.add(inWindow ? dayCell(cursor, counts.getOrDefault(cursor, 0L), today) : paddingCell());
      if (week.size() == DAYS_PER_WEEK) {
        weeks.add(week);
        week = new ArrayList<>();
      }
    }
    if (!week.isEmpty()) {
      while (week.size() < DAYS_PER_WEEK) {
        week.add(paddingCell());
      }
      weeks.add(week);
    }
    return weeks;
  }

  // One label per column: the short name of the month a column's first real day falls in, shown
  // only where the month changes, and dropped when the next change is too close to leave it room.
  private static List<String> monthLabels(final List<List<HeatmapDayCell>> weeks) {
    final List<String> labels = new ArrayList<>();
    YearMonth previous = null;
    int lastLabelAt = -1;
    for (int index = 0; index < weeks.size(); index++) {
      final YearMonth fallback = previous;
      final YearMonth month =
          weeks.get(index).stream()
              .filter(cell -> cell.date() != null)
              .findFirst()
              .map(cell -> YearMonth.from(cell.date()))
              .orElse(fallback);
      String label = "";
      if (month != null && !month.equals(previous)) {
        label = LocalizedDateFormatter.monthShort(month.getMonth(), AppLocales.current());
        if (lastLabelAt >= 0 && index - lastLabelAt < MIN_LABEL_SPACING) {
          labels.set(lastLabelAt, "");
        }
        lastLabelAt = index;
      }
      labels.add(label);
      previous = month;
    }
    return labels;
  }

  private static List<LoginActivityView.MonthTotal> monthTotals(final List<HeatmapDayCell> days) {
    final Map<YearMonth, Long> perMonth =
        days.stream()
            .collect(
                Collectors.groupingBy(
                    cell -> YearMonth.from(cell.date()),
                    TreeMap::new,
                    Collectors.summingLong(HeatmapDayCell::count)));
    final List<LoginActivityView.MonthTotal> months = new ArrayList<>();
    perMonth.forEach(
        (month, count) ->
            months.add(
                new LoginActivityView.MonthTotal(
                    LocalizedDateFormatter.monthYear(month, AppLocales.current()), count)));
    Collections.reverse(months);
    return months;
  }

  private static HeatmapDayCell dayCell(
      final LocalDate date, final long count, final LocalDate today) {
    final int level = level(count);
    final String when = readable(date);
    final String title;
    if (count == 0) {
      title = "No sign-ins on " + when;
    } else if (count == SINGLE) {
      title = "1 sign-in on " + when;
    } else {
      title = count + " sign-ins on " + when;
    }
    final String todayMark = date.equals(today) ? " clavaris-heatmap__day--today" : "";
    return new HeatmapDayCell(
        date,
        count,
        level,
        "clavaris-heatmap__day clavaris-heatmap__day--level-" + level + todayMark,
        title);
  }

  // LocalizedDateFormatter takes an Instant; a UTC calendar day is its own midnight.
  private static String readable(final LocalDate date) {
    return LocalizedDateFormatter.format(date.atStartOfDay(ZoneOffset.UTC).toInstant());
  }

  private static HeatmapDayCell paddingCell() {
    return new HeatmapDayCell(
        null, 0L, 0, "clavaris-heatmap__day clavaris-heatmap__day--empty", null);
  }

  // An if/else chain assigning one local rather than a nested ternary: a single exit point, as
  // PMD.OnlyOneReturn expects.
  private static int level(final long count) {
    final int level;
    if (count <= 0) {
      level = 0;
    } else if (count <= LEVEL_1_MAX) {
      level = 1;
    } else if (count <= LEVEL_2_MAX) {
      level = 2;
    } else if (count <= LEVEL_3_MAX) {
      level = 3;
    } else {
      level = 4;
    }
    return level;
  }
}
