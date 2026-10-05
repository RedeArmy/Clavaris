package com.clavaris.common.i18n;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Numbers, money, percentages and sizes in the reader's language: "1,234.5" / "1.234,5",
 * "$1,200.00" / "1.200,00 €", "10 MB" / "1,5 MB".
 *
 * <p>Nothing in the product shows money yet; {@link #currency} exists so the first screen that does
 * formats it correctly instead of concatenating a symbol. Sizes use decimal (SI) units, the way the
 * upload limit is stated (10 MB).
 */
public final class LocalizedNumbers {

  private static final long KILO = 1_000L;
  private static final long MEGA = 1_000_000L;
  private static final long GIGA = 1_000_000_000L;
  private static final int ONE_DECIMAL = 1;

  private LocalizedNumbers() {
    // Static helpers only.
  }

  public static String integer(final long value) {
    return integer(value, AppLocales.current());
  }

  public static String integer(final long value, final Locale locale) {
    return NumberFormat.getIntegerInstance(locale).format(value);
  }

  public static String decimal(final double value, final int maxFractionDigits) {
    return decimal(value, maxFractionDigits, AppLocales.current());
  }

  public static String decimal(
      final double value, final int maxFractionDigits, final Locale locale) {
    final NumberFormat format = NumberFormat.getNumberInstance(locale);
    format.setMaximumFractionDigits(maxFractionDigits);
    return format.format(value);
  }

  public static String percent(final double fraction, final Locale locale) {
    return NumberFormat.getPercentInstance(locale).format(fraction);
  }

  /**
   * @param isoCode an ISO 4217 code such as "USD" or "EUR"
   */
  public static String currency(
      final BigDecimal amount, final String isoCode, final Locale locale) {
    final NumberFormat format = NumberFormat.getCurrencyInstance(locale);
    format.setCurrency(Currency.getInstance(isoCode));
    return format.format(amount);
  }

  public static String fileSize(final long bytes) {
    return fileSize(bytes, AppLocales.current());
  }

  public static String fileSize(final long bytes, final Locale locale) {
    final String size;
    if (bytes >= GIGA) {
      size = decimal((double) bytes / GIGA, ONE_DECIMAL, locale) + " GB";
    } else if (bytes >= MEGA) {
      size = decimal((double) bytes / MEGA, ONE_DECIMAL, locale) + " MB";
    } else if (bytes >= KILO) {
      size = decimal((double) bytes / KILO, ONE_DECIMAL, locale) + " KB";
    } else {
      size = integer(bytes, locale) + " B";
    }
    return size;
  }
}
