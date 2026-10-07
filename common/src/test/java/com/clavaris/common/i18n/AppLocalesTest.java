package com.clavaris.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

class AppLocalesTest {

  @AfterEach
  void clear() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void englishAndSpanishAreTheSupportedLanguagesWithEnglishFirst() {
    assertThat(AppLocales.SUPPORTED).extracting(Locale::getLanguage).containsExactly("en", "es");
  }

  @Test
  void aRegionalVariantResolvesToItsSupportedLanguage() {
    assertThat(AppLocales.resolve(Locale.forLanguageTag("es-GT")).getLanguage()).isEqualTo("es");
    assertThat(AppLocales.resolve(Locale.forLanguageTag("en-GB")).getLanguage()).isEqualTo("en");
  }

  @Test
  void anUnsupportedOrMissingLanguageFallsBackToEnglish() {
    assertThat(AppLocales.resolve(Locale.FRENCH)).isEqualTo(AppLocales.ENGLISH);
    assertThat(AppLocales.resolve(null)).isEqualTo(AppLocales.ENGLISH);
  }

  @Test
  void tellsSpanishFromEnglishByLanguage() {
    assertThat(AppLocales.isSpanish(Locale.forLanguageTag("es-GT"))).isTrue();
    assertThat(AppLocales.isSpanish(AppLocales.ENGLISH)).isFalse();
  }

  @Test
  void theLanguageOfALocaleIsItsSupportedLanguageOrEnglish() {
    assertThat(AppLocales.languageOf(Locale.forLanguageTag("es-GT"))).isEqualTo("es");
    assertThat(AppLocales.languageOf(Locale.UK)).isEqualTo("en");
    assertThat(AppLocales.languageOf(Locale.FRENCH)).isEqualTo("en");
    assertThat(AppLocales.languageOf(null)).isEqualTo("en");
  }

  @Test
  void parsesLanguageTagsAndIgnoresGarbage() {
    assertThat(AppLocales.parse("ES")).contains(AppLocales.SPANISH);
    assertThat(AppLocales.parse("es_MX")).contains(AppLocales.SPANISH);
    assertThat(AppLocales.parse("klingon")).isEmpty();
    assertThat(AppLocales.parse("  ")).isEmpty();
    assertThat(AppLocales.parse(null)).isEmpty();
  }

  @Test
  void outsideARequestTheCurrentLanguageIsEnglishNotTheMachinesDefault() {
    assertThat(AppLocales.current()).isEqualTo(AppLocales.ENGLISH);
  }

  @Test
  void insideARequestTheCurrentLanguageFollowsTheRequest() {
    LocaleContextHolder.setLocale(Locale.forLanguageTag("es-ES"));

    assertThat(AppLocales.current().getLanguage()).isEqualTo("es");
  }
}
