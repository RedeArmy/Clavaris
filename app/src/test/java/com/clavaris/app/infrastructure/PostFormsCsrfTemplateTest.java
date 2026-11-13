package com.clavaris.app.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * TD-ARCH-050: Spring Security adds the CSRF field to every form whose action is written with
 * {@code th:action} and whose method is POST, so a template never writes it. A form that does would
 * render two (the page of the application says so; {@code CsrfHiddenFieldIntegrationTest} counts
 * them), and a POST form that is not {@code th:action} would render none and fail on submit with a
 * 403 only a browser would show. This reads every template of every module and holds both ends of
 * that.
 */
class PostFormsCsrfTemplateTest {

  private static final Pattern FORM =
      Pattern.compile("<form\\b([^>]*)>(.*?)</form>", Pattern.DOTALL);

  @Test
  void everyPostFormUsesThActionAndWritesNoCsrfFieldOfItsOwn() throws IOException {
    final List<String> offenders = new ArrayList<>();
    int postForms = 0;
    for (final Resource template :
        new PathMatchingResourcePatternResolver().getResources("classpath*:templates/**/*.html")) {
      final String html = new String(template.getContentAsByteArray(), StandardCharsets.UTF_8);
      final Matcher forms = FORM.matcher(html);
      while (forms.find()) {
        final String attributes = forms.group(1);
        if (!attributes.matches("(?s).*\\bmethod\\s*=\\s*\"post\".*")) {
          continue;
        }
        postForms++;
        final String where = template.getDescription() + ": <form" + attributes.strip();
        if (!attributes.contains("th:action")) {
          offenders.add("no th:action, so no automatic CSRF field -> " + where);
        }
        if (forms.group(2).contains("_csrf.parameterName")) {
          offenders.add("writes its own _csrf field next to the automatic one -> " + where);
        }
      }
    }

    assertThat(postForms).as("POST forms found in the templates").isGreaterThan(50);
    assertThat(offenders).isEmpty();
  }
}
