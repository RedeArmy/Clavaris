package com.clavaris.app.infrastructure.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Not a check: writes the list of translatable units to the file named by {@code
 * -Di18n.dump=<path>} (one per line: context, text, templates), for translators and for seeding a
 * catalogue. Does nothing when the property is absent, so it never runs in a normal build.
 */
class DumpTemplateUnitsTool {

  @Test
  void dumpWhenAsked() throws IOException {
    final String target = System.getProperty("i18n.dump");
    if (target != null) {
      final List<String> lines =
          TemplateUnits.extract().stream()
              .map(
                  unit ->
                      unit.context()
                          + "\t"
                          + unit.text().replace("\t", " ")
                          + "\t"
                          + String.join(",", unit.files()))
              .toList();
      Files.write(Path.of(target), lines, StandardCharsets.UTF_8);
    }
  }
}
