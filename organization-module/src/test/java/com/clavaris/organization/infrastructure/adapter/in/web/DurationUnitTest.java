package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DurationUnitTest {

  @Test
  void eachUnitIsWorthTheMinutesTheDomainsBoundsAssume() {
    assertThat(DurationUnit.MINUTES.toMinutes(7)).isEqualTo(7);
    assertThat(DurationUnit.HOURS.toMinutes(18)).isEqualTo(1_080);
    assertThat(DurationUnit.DAYS.toMinutes(1)).isEqualTo(1_440);
    assertThat(DurationUnit.WEEKS.toMinutes(1)).isEqualTo(10_080);
    // A month is 30 days and a year 365, so the domain's bounds round-trip exactly.
    assertThat(DurationUnit.MONTHS.toMinutes(2)).isEqualTo(2L * 30 * 1_440);
    assertThat(DurationUnit.YEARS.toMinutes(1)).isEqualTo(525_600);
    assertThat(DurationUnit.YEARS.toMinutes(10)).isEqualTo(5_256_000);
  }

  @Test
  void aStoredValueIsShownInTheLargestUnitThatStatesItExactly() {
    assertThat(DurationUnit.bestFit(1_080))
        .isEqualTo(new DurationUnit.Amount(18, DurationUnit.HOURS));
    assertThat(DurationUnit.bestFit(10_080))
        .isEqualTo(new DurationUnit.Amount(1, DurationUnit.WEEKS));
    assertThat(DurationUnit.bestFit(1_440))
        .isEqualTo(new DurationUnit.Amount(1, DurationUnit.DAYS));
    assertThat(DurationUnit.bestFit(43_200))
        .isEqualTo(new DurationUnit.Amount(1, DurationUnit.MONTHS));
    assertThat(DurationUnit.bestFit(86_400))
        .isEqualTo(new DurationUnit.Amount(2, DurationUnit.MONTHS));
    assertThat(DurationUnit.bestFit(525_600))
        .isEqualTo(new DurationUnit.Amount(1, DurationUnit.YEARS));
    assertThat(DurationUnit.bestFit(5_256_000))
        .isEqualTo(new DurationUnit.Amount(10, DurationUnit.YEARS));
  }

  @Test
  void aValueThatIsNotAWholeNumberOfALargerUnitStaysInMinutes() {
    assertThat(DurationUnit.bestFit(5)).isEqualTo(new DurationUnit.Amount(5, DurationUnit.MINUTES));
    assertThat(DurationUnit.bestFit(90))
        .isEqualTo(new DurationUnit.Amount(90, DurationUnit.MINUTES));
    assertThat(DurationUnit.bestFit(1_441))
        .isEqualTo(new DurationUnit.Amount(1_441, DurationUnit.MINUTES));
  }

  @Test
  void aBoundIsDescribedInWordsWithTheSingularWhereItIsOne() {
    assertThat(DurationUnit.describe(5)).isEqualTo("5 minutes");
    assertThat(DurationUnit.describe(1)).isEqualTo("1 minute");
    assertThat(DurationUnit.describe(60)).isEqualTo("1 hour");
    assertThat(DurationUnit.describe(1_080)).isEqualTo("18 hours");
    assertThat(DurationUnit.describe(525_600)).isEqualTo("1 year");
    assertThat(DurationUnit.describe(5_256_000)).isEqualTo("10 years");
  }

  @Test
  void aUnitIsFoundByItsCodeInAnyCaseAndNeverGuessed() {
    assertThat(DurationUnit.parse("hours")).contains(DurationUnit.HOURS);
    assertThat(DurationUnit.parse(" YEARS ")).contains(DurationUnit.YEARS);
    assertThat(DurationUnit.parse("fortnights")).isEmpty();
    assertThat(DurationUnit.parse("")).isEmpty();
    assertThat(DurationUnit.parse(null)).isEmpty();
  }
}
