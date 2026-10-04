package com.clavaris.identity.infrastructure.adapter.in.web;

import java.util.List;

/**
 * What the "Activity" card on an Account's profile renders, precomputed so the template only lays
 * it out: the Sunday-aligned calendar grid, one month label per week column (empty where none is
 * shown), the headline totals, and a per-month table that is the accessible twin of the grid.
 *
 * @param weeks the calendar columns, each exactly seven cells
 * @param monthLabels one entry per week: the short month name above that column, or an empty string
 * @param totalSignIns every sign-in inside the 365-day window
 * @param activeDays the number of days with at least one sign-in
 * @param longestStreak the longest run of consecutive days with a sign-in
 * @param months per-month totals, newest first
 */
public record LoginActivityView(
    List<List<HeatmapDayCell>> weeks,
    List<String> monthLabels,
    long totalSignIns,
    long activeDays,
    long longestStreak,
    List<MonthTotal> months) {

  /** One row of the per-month table. */
  public record MonthTotal(String label, long count) {}

  public boolean isEmpty() {
    return totalSignIns == 0;
  }

  /** "366 sign-ins on 243 days", with the singular where the count is one. */
  public String summary() {
    return plural(totalSignIns, "sign-in") + " on " + plural(activeDays, "day");
  }

  private static String plural(final long count, final String noun) {
    return count + " " + noun + (count == 1 ? "" : "s");
  }
}
