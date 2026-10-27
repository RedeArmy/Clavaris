package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * TD-FUT-048: the build minifies the stylesheets with a whitespace-and-comments pass (see the
 * antrun execution in app/pom.xml). A real CSS compressor was tried first and silently dropped a
 * whole rule that uses {@code min()} and {@code dvh}, so the pass was made to read no value at all,
 * and this test is what keeps it that way: the minified file must be the source with nothing
 * removed but comments, whitespace and the last semicolon of a block. Comparing the two with all
 * whitespace removed proves no token, rule or declaration was lost or altered.
 *
 * <p>It also guards the one assumption the pass relies on: no quoted string or {@code url()} in the
 * source holds a space beside one of the characters the pass trims, or it would be edited.
 */
class StylesheetMinificationTest {

  private static final Path SOURCE = Path.of("src", "main", "resources", "static", "css");
  private static final Path BUILT = Path.of("target", "classes", "static", "css");

  private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
  private static final Pattern PROTECTED_VALUE =
      Pattern.compile("\"[^\"\\n]*\"|'[^'\\n]*'|url\\([^)]*\\)");
  private static final Pattern TRIMMED_NEXT_TO_SPACE = Pattern.compile("[{};,:]\\s|\\s[{};,]");

  private static String withoutCommentsAndSpace(final String css) {
    return COMMENT.matcher(css).replaceAll("").replaceAll("\\s+", "").replace(";}", "}");
  }

  @ParameterizedTest
  @ValueSource(strings = {"clavaris.css", "clavaris-dashboard.css", "consumer.css"})
  void theMinifiedStylesheetIsTheSourceWithOnlyCommentsAndWhitespaceRemoved(final String name)
      throws IOException {
    final String source = Files.readString(SOURCE.resolve(name));
    final String built = Files.readString(BUILT.resolve(name));

    assertThat(withoutCommentsAndSpace(built))
        .as("%s: nothing but comments, whitespace and a block's last semicolon may differ", name)
        .isEqualTo(withoutCommentsAndSpace(source));
    assertThat(built)
        .as("%s was actually minified", name)
        .doesNotContain("/*")
        .doesNotContain("\n");
    assertThat(built.length()).isLessThan(source.length());
  }

  @ParameterizedTest
  @ValueSource(strings = {"clavaris.css", "clavaris-dashboard.css", "consumer.css"})
  void noStringOrUrlHoldsASpaceBesideACharacterTheMinifierTrims(final String name)
      throws IOException {
    final String css = COMMENT.matcher(Files.readString(SOURCE.resolve(name))).replaceAll("");

    final Matcher value = PROTECTED_VALUE.matcher(css);
    while (value.find()) {
      assertThat(TRIMMED_NEXT_TO_SPACE.matcher(value.group()).find())
          .as("%s: %s would be changed by the minifier", name, value.group())
          .isFalse();
    }
  }
}
