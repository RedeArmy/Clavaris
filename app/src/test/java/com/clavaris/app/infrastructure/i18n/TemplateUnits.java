package com.clavaris.app.infrastructure.i18n;

import com.clavaris.app.infrastructure.i18n.LocalizationDialect.ExtractedUnit;
import com.clavaris.common.i18n.AppLocales;
import com.clavaris.common.i18n.MessageCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * Lists every translatable unit in the repository's templates, using the same segmentation the
 * running application translates with (the {@link LocalizationDialect} in extraction mode), so the
 * list a translator sees is exactly what the page asks the catalogue for.
 *
 * <p>The templates live in other modules, so the scan starts from the repository root: the test
 * runs from the {@code app} directory and looks one level up.
 */
final class TemplateUnits {

  /** A unit and the templates it appears in. */
  record Found(String context, String text, java.util.Set<String> files) {}

  private static final Pattern IDENTIFIER_LIKE =
      Pattern.compile("^[a-z0-9_.:/#\\[\\]()@{}$=<>!&|,;*+~%^\\\\-]+$");
  private static final Pattern CODE_LITERAL =
      Pattern.compile(
          "^(?:[A-Z][A-Z0-9_]+|[a-z]+(?:[A-Z][a-z]+)+|[a-z-]+|/.*|#.*|\\.\\w.*|\\d.*)$");

  private TemplateUnits() {
    // Static helpers only.
  }

  static Path repositoryRoot() {
    return Path.of("..").toAbsolutePath().normalize();
  }

  /** Every template under any module's {@code src/main/resources/templates}. */
  static List<Path> templates() throws IOException {
    try (Stream<Path> files = Files.walk(repositoryRoot())) {
      return files
          .filter(path -> path.toString().endsWith(".html"))
          .filter(
              path -> path.toString().replace('\\', '/').contains("/src/main/resources/templates/"))
          .filter(path -> !path.toString().replace('\\', '/').contains("/target/"))
          .sorted()
          .toList();
    }
  }

  /** Units that carry words, de-duplicated by context and text, with their templates. */
  static List<Found> extract() throws IOException {
    final Map<String, Found> found = new TreeMap<>();
    for (final Path template : templates()) {
      final String name = repositoryRoot().relativize(template).toString().replace('\\', '/');
      final List<ExtractedUnit> units = new ArrayList<>();
      final StringTemplateResolver resolver = new StringTemplateResolver();
      resolver.setTemplateMode(TemplateMode.HTML);
      final SpringTemplateEngine engine = new SpringTemplateEngine();
      engine.setTemplateResolver(resolver);
      engine.setDialect(new LocalizationDialect(locale -> MessageCatalog.empty(), units::add));
      engine.process(
          Files.readString(template, StandardCharsets.UTF_8), new Context(AppLocales.ENGLISH));
      for (final ExtractedUnit unit : units) {
        if (worthTranslating(unit)) {
          final String key = unit.context() + "\u0004" + unit.text();
          found
              .computeIfAbsent(key, k -> new Found(unit.context(), unit.text(), new TreeSet<>()))
              .files()
              .add(name);
        }
      }
    }
    return found.values().stream()
        .sorted(Comparator.comparing(Found::text).thenComparing(Found::context))
        .toList();
  }

  // Plain attributes and text are always worth a look; a string literal inside a th:* expression is
  // only UI text when it reads like a phrase (not a CSS class, a path, an enum constant, a key).
  private static boolean worthTranslating(final ExtractedUnit unit) {
    final String text = unit.text();
    boolean worth = !text.isBlank();
    if (unit.context().startsWith("literal:")) {
      worth =
          text.codePoints().anyMatch(Character::isLetter)
              && text.length() > 1
              && !IDENTIFIER_LIKE.matcher(text).matches()
              && !CODE_LITERAL.matcher(text).matches()
              && !text.contains("clavaris-");
    }
    return worth;
  }
}
