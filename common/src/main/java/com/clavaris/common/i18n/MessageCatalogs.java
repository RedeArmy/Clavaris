package com.clavaris.common.i18n;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The loaded catalogue for each supported language, read once from the classpath.
 *
 * <p>English is the language the templates are written in, so it has no catalogue: asking for it
 * returns an empty one and every page renders its source text untouched.
 */
public final class MessageCatalogs {

  private static final Map<String, MessageCatalog> CACHE = new ConcurrentHashMap<>();

  private MessageCatalogs() {
    // Static helpers only.
  }

  public static MessageCatalog forLocale(final Locale locale) {
    final String language = AppLocales.languageOf(locale);
    return AppLocales.ENGLISH_LANGUAGE.equals(language)
        ? MessageCatalog.empty()
        : CACHE.computeIfAbsent(language, MessageCatalog::load);
  }
}
