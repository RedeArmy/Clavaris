package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class HtmlCommentStrippingDialectTest {

  private static String render(final String template) {
    final StringTemplateResolver resolver = new StringTemplateResolver();
    resolver.setTemplateMode(TemplateMode.HTML);
    final SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    engine.addDialect(new HtmlCommentStrippingDialect());
    return engine.process(template, new Context());
  }

  @Test
  void anOrdinaryCommentNeverReachesTheBrowser() {
    final String html =
        render("<p>Hello <!-- ADR-0010: an internal design note --><b>there</b></p>");

    assertThat(html).isEqualTo("<p>Hello <b>there</b></p>");
  }

  @Test
  void aMultiLineCommentIsDroppedToo() {
    final String html = render("<div>\n<!-- line one\n     line two -->\n<span>x</span></div>");

    assertThat(html).doesNotContain("<!--").doesNotContain("line one").contains("<span>x</span>");
  }

  @Test
  void aConditionalCommentIsKeptBecauseBrowsersActOnIt() {
    final String html = render("<p><!--[if IE]><i>old</i><![endif]-->ok</p>");

    assertThat(html).contains("<!--[if IE]>").contains("ok");
  }

  @Test
  void aCommentMarkedWithABangIsKept() {
    final String html = render("<p><!--! shipped on purpose -->ok</p>");

    assertThat(html).contains("<!--! shipped on purpose -->");
  }

  @Test
  void thymeleafOwnExpressionsKeepWorkingAroundDroppedComments() {
    final Context context = new Context();
    context.setVariable("name", "Ada");
    final StringTemplateResolver resolver = new StringTemplateResolver();
    resolver.setTemplateMode(TemplateMode.HTML);
    final SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    engine.addDialect(new HtmlCommentStrippingDialect());

    final String html =
        engine.process("<!-- note --><p th:text=\"${name}\">x</p><!-- another -->", context);

    assertThat(html).isEqualTo("<p>Ada</p>");
  }
}
