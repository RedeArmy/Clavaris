package com.clavaris.app.infrastructure.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Finds the translatable units in the templates and, when asked, writes them out for translators.
 *
 * <p>The extraction always runs, so a template that can no longer be segmented fails the build. The
 * list is written only when {@code -Di18n.dump=<path>} names a file (one unit per line: context,
 * text, templates), for seeding or reviewing a catalogue.
 */
class TemplateUnitsDumpTest {

  @Test
  void findsTheUnitsAndWritesThemWhenAsked() throws IOException {
    final List<TemplateUnits.Found> units = TemplateUnits.extract();

    assertThat(units).isNotEmpty().allSatisfy(unit -> assertThat(unit.files()).isNotEmpty());

    final String target = System.getProperty("i18n.dump");
    if (target != null) {
      final List<String> lines =
          units.stream()
              .map(
                  unit ->
                      unit.context()
                          + "\t"
                          + unit.text().replace("\t", " ")
                          + "\t"
                          + String.join(",", unit.files()))
              .toList();
      Files.write(Path.of(target), lines, StandardCharsets.UTF_8);

      assertThat(Path.of(target)).isNotEmptyFile();
    }
  }
}
