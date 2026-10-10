package com.clavaris.app.infrastructure.i18n;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clavaris.common.i18n.MessageCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Every page the consuming application's people see names its browser tab "<brand> — <page>". The
 * brand is a runtime value, so the page title is never seen by the template check as one text: it
 * needs its own catalogue entry, {@code {0} — <page>}. This fails when a consumer page is added (or
 * its title changed) without that entry, instead of leaving the tab half in English in Spanish.
 */
class ConsumerTabTitlesCatalogTest {

  private static final Pattern TITLE = Pattern.compile("consumerHead\\('([^']*)'\\)");

  @Test
  void everyConsumerPageTitleHasABrandedSpanishEntry() throws IOException {
    final MessageCatalog catalog = MessageCatalog.load("es");
    final TreeSet<String> missing = new TreeSet<>();
    final TreeSet<String> titles = consumerPageTitles();
    assertFalse(titles.isEmpty(), "no consumer page titles found: the template scan is broken");

    for (final String title : titles) {
      final Optional<String> branded = catalog.translate("title", "Acme Analytics — " + title);
      final Optional<String> plain = catalog.translate("title", title);
      if (branded.isEmpty()
          || plain.isEmpty()
          || !branded.get().equals("Acme Analytics — " + plain.get())) {
        missing.add(title);
      }
    }

    assertTrue(
        missing.isEmpty(),
        () ->
            "Consumer page titles with no Spanish entry for the branded tab ('{0} — <title>', and"
                + " the same words as the plain title): "
                + missing);
  }

  @Test
  void aBrandedTabKeepsTheBrandNameUntranslated() throws IOException {
    final MessageCatalog catalog = MessageCatalog.load("es");

    // The brand is whatever the application or its Organization is called, so it is never run
    // through the catalogue: a name that happens to be a translatable word stays as typed.
    assertTrue(
        catalog
            .translate("title", "Sign in — Sign in")
            .map(text -> text.startsWith("Sign in — "))
            .orElse(true));
  }

  private static TreeSet<String> consumerPageTitles() throws IOException {
    final TreeSet<String> titles = new TreeSet<>();
    final Resource[] templates =
        new PathMatchingResourcePatternResolver()
            .getResources("classpath*:templates/identity/**/*.html");
    for (final Resource template : templates) {
      final String source =
          new String(template.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final Matcher matcher = TITLE.matcher(source);
      while (matcher.find()) {
        titles.add(matcher.group(1));
      }
    }
    return titles;
  }
}
