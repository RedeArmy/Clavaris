package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * TD-FUT-048: the stylesheet is split in two, {@code clavaris.css} for every page and {@code
 * clavaris-dashboard.css} for Clavaris's own pages only, and a consuming application's pages link
 * only the first. The one mistake that split can hide is a class a consumer-facing page uses whose
 * rules exist only in the second file: nothing fails, the element just renders unstyled. This finds
 * it before a person does.
 *
 * <p>The classes a consumer-facing page can carry are read from the templates that use {@code
 * consumerHead} and every fragment they pull in, plus the class names the server writes into markup
 * itself (the language switcher, the rate-limit page), read from the Java sources that name a
 * {@code clavaris-} class. A class is only reported when it is defined in the dashboard file and in
 * no rule of the core one.
 */
class StylesheetSplitTest {

  private static final Pattern INCLUDE =
      Pattern.compile("th:(?:replace|insert|include)=\"~?\\{?\\s*([A-Za-z0-9_/\\-]+)");
  private static final Pattern CLASS_ATTRIBUTE =
      Pattern.compile("(?:class|th:class|th:classappend)=\"([^\"]*)\"");
  // Any quoted string; the "clavaris" filter is applied in code, so the pattern has no two
  // unbounded runs around a literal (which backtracks quadratically).
  private static final Pattern QUOTED = Pattern.compile("'([^']*)'");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_\\-]*");
  private static final Pattern CSS_CLASS = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_\\-]*)");
  private static final Pattern JAVA_STRING_LITERAL = Pattern.compile("\"([^\"\\n]*)\"");

  private static String read(final Resource resource) throws IOException {
    try (InputStream in = resource.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Map<String, String> allTemplates() throws IOException {
    final Map<String, String> templates = new HashMap<>();
    final String marker = "/templates/";
    for (final Resource resource :
        new PathMatchingResourcePatternResolver().getResources("classpath*:templates/**/*.html")) {
      final String url = resource.getURL().toString();
      final String name = url.substring(url.lastIndexOf(marker) + marker.length());
      templates.put(name.substring(0, name.length() - ".html".length()), read(resource));
    }
    return templates;
  }

  private static Set<String> classesUsedByConsumerPages() throws IOException {
    final Map<String, String> templates = allTemplates();
    final Set<String> consumer = new HashSet<>();
    templates.forEach(
        (name, html) -> {
          if (html.contains("consumerHead") && !name.startsWith("fragments/")) {
            consumer.add(name);
          }
        });
    final Set<String> frontier = new HashSet<>(consumer);
    while (!frontier.isEmpty()) {
      final String next = frontier.iterator().next();
      frontier.remove(next);
      final Matcher includes = INCLUDE.matcher(templates.get(next));
      while (includes.find()) {
        final String included = includes.group(1);
        if (templates.containsKey(included)
            && !included.equals("fragments/head")
            && consumer.add(included)) {
          frontier.add(included);
        }
      }
    }
    assertThat(consumer).as("consumer-facing templates found").hasSizeGreaterThan(20);

    final Set<String> classes = new HashSet<>();
    for (final String name : consumer) {
      final String html = templates.get(name);
      final Matcher attributes = CLASS_ATTRIBUTE.matcher(html);
      while (attributes.find()) {
        addTokens(classes, attributes.group(1));
      }
      final Matcher literals = QUOTED.matcher(html);
      while (literals.find()) {
        if (literals.group(1).contains("clavaris")) {
          addTokens(classes, literals.group(1));
        }
      }
    }
    for (final String module : new String[] {"../app", "../common", "../identity-module"}) {
      final Path sources = Path.of(module, "src", "main", "java");
      if (Files.isDirectory(sources)) {
        try (Stream<Path> files = Files.walk(sources)) {
          for (final Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
            final Matcher literals = JAVA_STRING_LITERAL.matcher(Files.readString(file));
            while (literals.find()) {
              if (literals.group(1).contains("clavaris-")) {
                addTokens(classes, literals.group(1));
              }
            }
          }
        }
      }
    }
    return classes;
  }

  private static void addTokens(final Set<String> into, final String text) {
    final Matcher token = TOKEN.matcher(text);
    while (token.find()) {
      into.add(token.group());
    }
  }

  private static Set<String> classesDefinedIn(final String stylesheet) throws IOException {
    final String css =
        read(new PathMatchingResourcePatternResolver()
                .getResource("classpath:static/css/" + stylesheet))
            // Comments mention class names in prose; they define nothing.
            .replaceAll("(?s)/\\*.*?\\*/", "");
    final Set<String> classes = new HashSet<>();
    final Matcher selector = CSS_CLASS.matcher(css);
    while (selector.find()) {
      classes.add(selector.group(1));
    }
    return classes;
  }

  @Test
  void noClassAConsumerFacingPageUsesIsDefinedOnlyInTheDashboardStylesheet() throws IOException {
    final Set<String> usedByConsumerPages = classesUsedByConsumerPages();
    final Set<String> core = classesDefinedIn("clavaris.css");
    final Set<String> dashboardOnly = classesDefinedIn("clavaris-dashboard.css");
    dashboardOnly.removeAll(core);

    // Only Clavaris's own namespace: a bare word such as "active" appears in templates as prose and
    // as a state class alike, and is styled by rules that need a Clavaris class beside it.
    final Set<String> stranded = new HashSet<>(dashboardOnly);
    stranded.retainAll(usedByConsumerPages);
    stranded.removeIf(name -> !name.startsWith("clavaris-"));

    assertThat(stranded)
        .as(
            "classes used by consumer-facing pages whose rules are only in clavaris-dashboard.css;"
                + " move their rules to clavaris.css")
        .isEmpty();
  }

  @Test
  void theSplitIsReal() throws IOException {
    final Set<String> core = classesDefinedIn("clavaris.css");
    final Set<String> dashboardOnly = classesDefinedIn("clavaris-dashboard.css");
    dashboardOnly.removeAll(core);

    assertThat(core).as("classes defined for every page").hasSizeGreaterThan(50);
    assertThat(dashboardOnly).as("classes only the dashboard pays for").hasSizeGreaterThan(200);
  }
}
