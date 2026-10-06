package com.clavaris.common.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * The languages the interface is available in, and how the current one is found.
 *
 * <p>Adding a language is a matter of listing it in {@link #SUPPORTED}, adding its catalogue
 * ({@code i18n/messages_<language>.po}) and its autonym to the language switcher; nothing else in
 * the code assumes there are exactly two.
 *
 * <p>{@link #current()} never returns a language that is not supported, and falls back to English
 * when no request is being served (a unit test, a scheduled job): the JVM's own default locale is
 * deliberately not consulted, so a developer's Spanish laptop renders the same pages as a build
 * server.
 */
public final class AppLocales {

  public static final Locale ENGLISH = Locale.ENGLISH;
  public static final Locale SPANISH = Locale.forLanguageTag("es");

  /** Every supported locale, the first being the default. */
  public static final List<Locale> SUPPORTED = List.of(ENGLISH, SPANISH);

  public static final String SPANISH_LANGUAGE = "es";

  private AppLocales() {
    // Static helpers only.
  }

  /** The supported locale for the request being served, or English outside a request. */
  public static Locale current() {
    final LocaleContext context = LocaleContextHolder.getLocaleContext();
    return context == null ? ENGLISH : resolve(context.getLocale());
  }

  /** The supported locale that shares {@code requested}'s language, else English. */
  public static Locale resolve(final Locale requested) {
    return parse(requested == null ? null : requested.getLanguage()).orElse(ENGLISH);
  }

  /** The supported locale for a language tag such as "es", "es-GT" or "EN", if there is one. */
  public static Optional<Locale> parse(final String tag) {
    Optional<Locale> match = Optional.empty();
    if (tag != null && !tag.isBlank()) {
      final String language = Locale.forLanguageTag(tag.strip().replace('_', '-')).getLanguage();
      match =
          SUPPORTED.stream().filter(locale -> locale.getLanguage().equals(language)).findFirst();
    }
    return match;
  }

  public static boolean isSpanish(final Locale locale) {
    return SPANISH_LANGUAGE.equals(locale.getLanguage());
  }
}
