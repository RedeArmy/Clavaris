package com.clavaris.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LocalizedNumbersTest {

  @Test
  void groupsAndSeparatesDecimalsTheWayEachLanguageDoes() {
    assertThat(LocalizedNumbers.integer(1_234_567, AppLocales.ENGLISH)).isEqualTo("1,234,567");
    assertThat(LocalizedNumbers.integer(1_234_567, AppLocales.SPANISH)).isEqualTo("1.234.567");
    assertThat(LocalizedNumbers.decimal(1234.5, 2, AppLocales.ENGLISH)).isEqualTo("1,234.5");
    assertThat(LocalizedNumbers.decimal(12345.5, 2, AppLocales.SPANISH)).isEqualTo("12.345,5");
  }

  @Test
  void sizesUseDecimalUnitsAndTheLanguagesDecimalSeparator() {
    assertThat(LocalizedNumbers.fileSize(10_000_000, AppLocales.ENGLISH)).isEqualTo("10 MB");
    assertThat(LocalizedNumbers.fileSize(1_500_000, AppLocales.ENGLISH)).isEqualTo("1.5 MB");
    assertThat(LocalizedNumbers.fileSize(1_500_000, AppLocales.SPANISH)).isEqualTo("1,5 MB");
    assertThat(LocalizedNumbers.fileSize(2_048, AppLocales.ENGLISH)).isEqualTo("2 KB");
    assertThat(LocalizedNumbers.fileSize(512, AppLocales.SPANISH)).isEqualTo("512 B");
  }

  @Test
  void currencyUsesTheLanguagesSymbolPlacementAndSeparators() {
    assertThat(LocalizedNumbers.currency(new BigDecimal("1200.5"), "USD", AppLocales.ENGLISH))
        .isEqualTo("$1,200.50");
    assertThat(LocalizedNumbers.currency(new BigDecimal("1200.5"), "EUR", AppLocales.SPANISH))
        .contains("1.200,50")
        .contains("€");
  }

  @Test
  void percentages() {
    assertThat(LocalizedNumbers.percent(0.256, AppLocales.ENGLISH)).isEqualTo("26%");
    assertThat(LocalizedNumbers.percent(0.256, AppLocales.SPANISH)).contains("26").contains("%");
  }
}
