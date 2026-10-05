package com.clavaris.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.i18n.PoParser.PoEntry;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class PoParserTest {

  private static List<PoEntry> parse(final String po) {
    return PoParser.parse(new StringReader(po));
  }

  @Test
  void readsAnEntryWithItsTranslation() {
    final List<PoEntry> entries = parse("msgid \"Cancel\"\nmsgstr \"Cancelar\"\n");

    assertThat(entries).containsExactly(new PoEntry("", "Cancel", "Cancelar"));
  }

  @Test
  void readsSeveralEntriesAndSkipsCommentsAndTheHeader() {
    final List<PoEntry> entries =
        parse(
            "# translator note\nmsgid \"\"\nmsgstr \"Language: es\\n\"\n\n"
                + "#: some/template.html:3\n#, fuzzy\nmsgid \"Save\"\nmsgstr \"Guardar\"\n\n"
                + "msgid \"Next\"\nmsgstr \"Siguiente\"\n");

    assertThat(entries)
        .containsExactly(new PoEntry("", "Save", "Guardar"), new PoEntry("", "Next", "Siguiente"));
  }

  @Test
  void joinsAMessageContinuedOverSeveralLines() {
    final List<PoEntry> entries =
        parse(
            "msgid \"\"\n\"Read this \"\n\"carefully.\"\nmsgstr \"\"\n\"Lee esto \"\n\"con atención.\"\n");

    assertThat(entries)
        .containsExactly(new PoEntry("", "Read this carefully.", "Lee esto con atención."));
  }

  @Test
  void keepsAContextAndUnescapesQuotes() {
    final List<PoEntry> entries =
        parse(
            "msgctxt \"th\"\nmsgid \"Created\"\nmsgstr \"Creado\"\n\n"
                + "msgid \"Say \\\"hi\\\"\"\nmsgstr \"Di \\\"hola\\\"\"\n");

    assertThat(entries)
        .containsExactly(
            new PoEntry("th", "Created", "Creado"), new PoEntry("", "Say \"hi\"", "Di \"hola\""));
  }

  @Test
  void anEntryWithoutATranslationIsKeptEmptyForTheCoverageCheck() {
    final List<PoEntry> entries = parse("msgid \"Pending\"\nmsgstr \"\"\n");

    assertThat(entries).containsExactly(new PoEntry("", "Pending", ""));
  }
}
