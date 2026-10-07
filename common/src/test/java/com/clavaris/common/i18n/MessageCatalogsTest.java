package com.clavaris.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class MessageCatalogsTest {

  @Test
  void englishNeedsNoCatalogue() {
    assertThat(MessageCatalogs.forLocale(Locale.ENGLISH)).isSameAs(MessageCatalog.empty());
    assertThat(MessageCatalogs.forLocale(Locale.UK)).isSameAs(MessageCatalog.empty());
  }

  @Test
  void aMissingOrUnsupportedLocaleIsServedAsEnglish() {
    assertThat(MessageCatalogs.forLocale(null)).isSameAs(MessageCatalog.empty());
    assertThat(MessageCatalogs.forLocale(Locale.FRENCH)).isSameAs(MessageCatalog.empty());
  }

  @Test
  void aSupportedLanguageGetsItsOwnCatalogueOnce() {
    final MessageCatalog first = MessageCatalogs.forLocale(AppLocales.SPANISH);

    assertThat(first).isNotSameAs(MessageCatalog.empty());
    assertThat(MessageCatalogs.forLocale(Locale.forLanguageTag("es-GT"))).isSameAs(first);
  }
}
