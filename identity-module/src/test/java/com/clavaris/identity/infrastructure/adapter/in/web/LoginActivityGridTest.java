package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.application.usecase.getloginactivityforaccount.GetLoginActivityForAccountService;
import com.clavaris.identity.application.usecase.getloginactivityforaccount.LoginActivityDay;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginActivityGridTest {

  // A fixed Saturday, so the grid's last column is a full week and the arithmetic is easy to
  // follow.
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

  private static List<HeatmapDayCell> cells(final LoginActivityView view) {
    return view.weeks().stream().flatMap(List::stream).toList();
  }

  private static HeatmapDayCell cell(final LoginActivityView view, final LocalDate date) {
    return cells(view).stream().filter(c -> date.equals(c.date())).findFirst().orElseThrow();
  }

  @Test
  void drawsExactlyTheTrailingWindowOfRealDaysInWholeSundayAlignedWeeks() {
    final LoginActivityView view = LoginActivityGrid.build(List.of(), TODAY);

    assertThat(cells(view).stream().filter(c -> c.date() != null).count())
        .isEqualTo(GetLoginActivityForAccountService.WINDOW_DAYS);
    assertThat(view.weeks()).allSatisfy(week -> assertThat(week).hasSize(7));
    // Each column starts on a Sunday; only the first one may begin with padding.
    final HeatmapDayCell lastColumnTop = view.weeks().get(view.weeks().size() - 1).get(0);
    assertThat(lastColumnTop.date().getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
    assertThat(view.monthLabels()).hasSameSizeAs(view.weeks());
  }

  @Test
  void anEmptyHistoryHasZeroTotalsAndNoStreak() {
    final LoginActivityView view = LoginActivityGrid.build(List.of(), TODAY);

    assertThat(view.isEmpty()).isTrue();
    assertThat(view.totalSignIns()).isZero();
    assertThat(view.activeDays()).isZero();
    assertThat(view.longestStreak()).isZero();
  }

  @Test
  void totalsAndActiveDaysAreCountedFromTheDaysDrawn() {
    final LoginActivityView view =
        LoginActivityGrid.build(
            List.of(
                new LoginActivityDay(TODAY, 2),
                new LoginActivityDay(TODAY.minusDays(3), 5),
                new LoginActivityDay(TODAY.minusDays(40), 1)),
            TODAY);

    assertThat(view.totalSignIns()).isEqualTo(8);
    assertThat(view.activeDays()).isEqualTo(3);
    assertThat(view.isEmpty()).isFalse();
  }

  @Test
  void theLongestStreakIsTheLongestRunOfConsecutiveActiveDays() {
    final LoginActivityView view =
        LoginActivityGrid.build(
            List.of(
                new LoginActivityDay(TODAY, 1),
                new LoginActivityDay(TODAY.minusDays(1), 4),
                new LoginActivityDay(TODAY.minusDays(10), 1),
                new LoginActivityDay(TODAY.minusDays(11), 1),
                new LoginActivityDay(TODAY.minusDays(12), 2),
                new LoginActivityDay(TODAY.minusDays(13), 1),
                new LoginActivityDay(TODAY.minusDays(20), 1)),
            TODAY);

    assertThat(view.longestStreak()).isEqualTo(4);
    assertThat(LoginActivityGrid.build(List.of(), TODAY).longestStreak()).isZero();
  }

  @Test
  void theSummaryReadsInPluralAndSingular() {
    assertThat(LoginActivityGrid.build(List.of(new LoginActivityDay(TODAY, 1)), TODAY).summary())
        .isEqualTo("1 sign-in on 1 day");
    assertThat(
            LoginActivityGrid.build(
                    List.of(
                        new LoginActivityDay(TODAY, 3),
                        new LoginActivityDay(TODAY.minusDays(1), 2)),
                    TODAY)
                .summary())
        .isEqualTo("5 sign-ins on 2 days");
  }

  @Test
  void aSignInOlderThanTheWindowIsNotCounted() {
    final LocalDate outside = TODAY.minusDays(GetLoginActivityForAccountService.WINDOW_DAYS);

    final LoginActivityView view =
        LoginActivityGrid.build(List.of(new LoginActivityDay(outside, 9)), TODAY);

    assertThat(view.totalSignIns()).isZero();
  }

  @Test
  void levelsAndTooltipsReadLikeAPersonWouldSayThem() {
    final LoginActivityView view =
        LoginActivityGrid.build(
            List.of(
                new LoginActivityDay(TODAY, 1),
                new LoginActivityDay(TODAY.minusDays(1), 2),
                new LoginActivityDay(TODAY.minusDays(2), 5),
                new LoginActivityDay(TODAY.minusDays(3), 9)),
            TODAY);

    assertThat(cell(view, TODAY).level()).isEqualTo(1);
    assertThat(cell(view, TODAY).title()).isEqualTo("1 sign-in on October 3rd, 2026");
    assertThat(cell(view, TODAY.minusDays(1)).level()).isEqualTo(2);
    assertThat(cell(view, TODAY.minusDays(1)).title()).isEqualTo("2 sign-ins on October 2nd, 2026");
    assertThat(cell(view, TODAY.minusDays(2)).level()).isEqualTo(3);
    assertThat(cell(view, TODAY.minusDays(3)).level()).isEqualTo(4);
    assertThat(cell(view, TODAY.minusDays(10)).title()).startsWith("No sign-ins on ");
  }

  @Test
  void todaysCellIsMarkedAndNoOtherIs() {
    final LoginActivityView view = LoginActivityGrid.build(List.of(), TODAY);

    assertThat(cell(view, TODAY).cssClass()).contains("clavaris-heatmap__day--today");
    assertThat(cells(view).stream().filter(c -> c.cssClass().contains("--today")).count())
        .isEqualTo(1);
  }

  @Test
  void paddingCellsAreNeverRealDays() {
    final LoginActivityView view = LoginActivityGrid.build(List.of(), TODAY);

    assertThat(cells(view).stream().filter(c -> c.date() == null))
        .allSatisfy(
            c -> {
              assertThat(c.title()).isNull();
              assertThat(c.cssClass()).contains("--empty");
            });
  }

  @Test
  void monthLabelsAppearOnceEachWithRoomBetweenThem() {
    final LoginActivityView view = LoginActivityGrid.build(List.of(), TODAY);

    final List<String> shown =
        view.monthLabels().stream().filter(label -> !label.isEmpty()).toList();
    assertThat(shown).doesNotHaveDuplicates();
    assertThat(shown.size()).isBetween(11, 13);
    int lastAt = -10;
    for (int index = 0; index < view.monthLabels().size(); index++) {
      if (!view.monthLabels().get(index).isEmpty()) {
        assertThat(index - lastAt).isGreaterThanOrEqualTo(3);
        lastAt = index;
      }
    }
  }

  @Test
  void monthlyTotalsAreNewestFirstAndAddUpToTheTotal() {
    final LoginActivityView view =
        LoginActivityGrid.build(
            List.of(new LoginActivityDay(TODAY, 4), new LoginActivityDay(TODAY.minusDays(40), 6)),
            TODAY);

    assertThat(view.months().get(0).label()).isEqualTo("October 2026");
    assertThat(view.months().stream().mapToLong(LoginActivityView.MonthTotal::count).sum())
        .isEqualTo(view.totalSignIns());
    assertThat(view.months().get(0).count()).isEqualTo(4);
  }
}
