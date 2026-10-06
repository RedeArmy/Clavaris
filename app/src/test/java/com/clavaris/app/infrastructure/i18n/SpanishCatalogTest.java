package com.clavaris.app.infrastructure.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clavaris.common.i18n.MessageCatalog;
import com.clavaris.common.i18n.PoParser;
import com.clavaris.common.i18n.PoParser.PoEntry;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps the Spanish catalogue honest: every piece of interface text the templates contain has a
 * translation, and no translation drops or invents a placeholder or an inline tag (which would
 * break the sentence or the markup around it).
 */
class SpanishCatalogTest {

  private static final Pattern TOKEN = Pattern.compile("\\{\\d+(?::t)?}|</?\\d+/?>");
  private static final String CATALOGUE = "/i18n/messages_es.po";

  // Units that are deliberately not translated through the catalogue: the phone field's country
  // options are named in the reader's language by the picker script, and the remaining literals are
  // DOM property names and attribute fragments inside expressions rather than wording.
  private static final Pattern NOT_WORDING =
      Pattern.compile("^(?:-hint|\\. Switch environment|Environment:|innerHTML|outerHTML)$");

  @Test
  void everyTemplateUnitIsTranslated() throws IOException {
    final MessageCatalog catalog = MessageCatalog.load("es");
    final TreeSet<String> missing = new TreeSet<>();
    for (final TemplateUnits.Found unit : TemplateUnits.extract()) {
      final boolean countryOption =
          "option".equals(unit.context())
              && unit.files().stream().allMatch(file -> file.endsWith("phone-field.html"));
      if (!countryOption
          && !NOT_WORDING.matcher(unit.text().strip()).matches()
          && catalog.translate(unit.context(), unit.text()).isEmpty()) {
        missing.add(unit.context() + "\t" + unit.text());
      }
    }
    assertTrue(
        missing.isEmpty(), () -> "Untranslated template text:\n" + String.join("\n", missing));
  }

  @Test
  void translationsKeepTheirPlaceholdersAndTags() throws IOException {
    final List<String> broken = new ArrayList<>();
    for (final PoEntry entry : entries()) {
      if (!entry.msgid().isEmpty() && !tokens(entry.msgid()).equals(tokens(entry.msgstr()))) {
        broken.add(entry.msgid());
      }
    }
    assertTrue(
        broken.isEmpty(), () -> "Placeholders or tags differ:\n" + String.join("\n", broken));
  }

  @Test
  void noSourceTextIsCataloguedTwice() throws IOException {
    final List<String> seen = new ArrayList<>();
    final List<String> duplicated = new ArrayList<>();
    for (final PoEntry entry : entries()) {
      final String key = entry.context() + "\u0004" + MessageCatalog.normalize(entry.msgid());
      if (seen.contains(key)) {
        duplicated.add(entry.msgid());
      }
      seen.add(key);
    }
    assertEquals(List.of(), duplicated);
  }

  private static List<PoEntry> entries() throws IOException {
    try (Reader reader =
        new InputStreamReader(
            SpanishCatalogTest.class.getResourceAsStream(CATALOGUE), StandardCharsets.UTF_8)) {
      return PoParser.parse(reader);
    }
  }

  private static List<String> tokens(final String text) {
    final List<String> found = new ArrayList<>();
    final Matcher matcher = TOKEN.matcher(text);
    while (matcher.find()) {
      found.add(matcher.group().replace(":t", ""));
    }
    found.sort(String::compareTo);
    return found;
  }
}
