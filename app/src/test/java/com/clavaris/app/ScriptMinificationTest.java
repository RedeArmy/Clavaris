package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * TD-FUT-051: the build minifies the page scripts with Closure Compiler in whitespace-only mode
 * (see the closure-compiler-maven-plugin execution in app/pom.xml). Whitespace-only parses the file
 * and prints it again without comments or layout; it renames and rewrites nothing, so the behaviour
 * is the source's. A real parser is the reason it is used rather than a regular expression, which
 * cannot tell a {@code //} in a URL or a regex literal from a comment.
 *
 * <p>This test guards what the build cannot: that the step ran on every script (the plugin skips an
 * output that looks newer than its source, which silently shipped the unminified files the first
 * time), that the scripts really got smaller, and that the already-minified library was not
 * touched. A script that does not parse fails the build itself.
 */
class ScriptMinificationTest {

  private static final Path SOURCE = Path.of("src", "main", "resources", "static", "js");
  private static final Path BUILT = Path.of("target", "classes", "static", "js");
  private static final String LIBRARY = "htmx.min.js";

  private static List<Path> scripts() throws IOException {
    try (Stream<Path> files = Files.list(SOURCE)) {
      return files
          .filter(path -> path.toString().endsWith(".js"))
          .filter(path -> !path.getFileName().toString().equals(LIBRARY))
          .sorted()
          .toList();
    }
  }

  @Test
  void everyScriptIsSmallerAndHasLostItsIndentation() throws IOException {
    assertThat(scripts()).as("scripts found").hasSizeGreaterThan(10);
    for (Path source : scripts()) {
      final String built = Files.readString(BUILT.resolve(source.getFileName()));
      assertThat(built.length())
          .as("%s was minified", source.getFileName())
          .isLessThan(Files.readString(source).length());
      // Closure still wraps a very long line; a break between statements is harmless, but no
      // indentation survives a real minification.
      assertThat(built).as("%s: no indentation left", source.getFileName()).doesNotContain("\n  ");
    }
  }

  @Test
  void everyScriptStillRunsInStrictModeAsItsSourceDid() throws IOException {
    for (Path source : scripts()) {
      assertThat(Files.readString(source))
          .as("%s declares strict mode in its source", source.getFileName())
          .contains("\"use strict\"");
      assertThat(Files.readString(BUILT.resolve(source.getFileName())))
          .as("%s keeps strict mode once minified", source.getFileName())
          .containsAnyOf("'use strict'", "\"use strict\"");
    }
  }

  @Test
  void theAlreadyMinifiedLibraryIsServedUntouched() throws IOException {
    assertThat(Files.readAllBytes(BUILT.resolve(LIBRARY)))
        .isEqualTo(Files.readAllBytes(SOURCE.resolve(LIBRARY)));
  }
}
