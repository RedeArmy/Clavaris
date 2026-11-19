package com.clavaris.app.infrastructure.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.clavaris.common.i18n.MessageCatalog;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.IWebRequest;

class LanguageSwitcherTest {

  private static ITemplateContext context(
      final String applicationPath,
      final String requestPath,
      final Map<String, String[]> parameters,
      final String errorRequestUri) {
    final IWebRequest request = mock(IWebRequest.class);
    when(request.getApplicationPath()).thenReturn(applicationPath);
    when(request.getRequestPath()).thenReturn(requestPath);
    when(request.getParameterMap()).thenReturn(parameters);
    final IWebExchange exchange = mock(IWebExchange.class);
    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getAttributeValue("jakarta.servlet.error.request_uri"))
        .thenReturn(errorRequestUri);
    // The engine's own context is both: a template context that also knows its web exchange.
    final IWebContext context =
        mock(IWebContext.class, withSettings().extraInterfaces(ITemplateContext.class));
    when(context.getExchange()).thenReturn(exchange);
    return (ITemplateContext) context;
  }

  private static String markup(final ITemplateContext context) {
    return LanguageSwitcher.markup(Locale.ENGLISH, MessageCatalog.load("es"), context);
  }

  @Test
  void linksToThePageBeingViewedWithItsOtherParametersAndTheTargetLanguage() {
    final String html =
        markup(context("/app", "/o/1/login", Map.of("next", new String[] {"/home"}), null));

    assertThat(html)
        .contains("href=\"/app/o/1/login?next=%2Fhome&amp;lang=en\"")
        .contains("href=\"/app/o/1/login?next=%2Fhome&amp;lang=es\"");
  }

  @Test
  void anErrorPageLinksToTheRequestThatCausedItNotToTheInternalErrorPath() {
    final String html =
        markup(
            context(
                "",
                "/error",
                Map.of("state", new String[] {"s"}, "lang", new String[] {"en"}),
                "/o/1/oauth2/authorize"));

    assertThat(html)
        .contains("href=\"/o/1/oauth2/authorize?state=s&amp;lang=es\"")
        .doesNotContain("/error");
  }
}
