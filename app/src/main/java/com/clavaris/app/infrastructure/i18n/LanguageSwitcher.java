package com.clavaris.app.infrastructure.i18n;

import com.clavaris.common.i18n.AppLocales;
import com.clavaris.common.i18n.MessageCatalog;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.web.IWebRequest;
import org.unbescape.html.HtmlEscape;

/**
 * The language switcher added to every full page: one link per supported language, each pointing at
 * the page being viewed with {@code ?lang=xx}, which the locale interceptor turns into a cookie.
 *
 * <p>It is plain links (no script, no form), so it works with JavaScript disabled and under the
 * strictest page policy. Each link carries the language's own name ("Español", never "Spanish"),
 * its {@code lang} so a screen reader pronounces it correctly, and {@code aria-current} on the
 * language in use.
 */
// PMD.LawOfDemeter: reading the request out of a Thymeleaf web context is a chain of calls on the
// engine's own types (exchange, request, parameter map); there is no shorter way to the URL.
@SuppressWarnings("PMD.LawOfDemeter")
final class LanguageSwitcher {

  /* default */ static final String PARAMETER = "lang";
  private static final String LABEL = "Language";

  private LanguageSwitcher() {
    // Static helpers only.
  }

  /* default */ static String markup(
      final Locale current, final MessageCatalog catalog, final ITemplateContext context) {
    final String label = catalog.translate("", LABEL).orElse(LABEL);
    final StringBuilder html = new StringBuilder();
    html.append("<nav class=\"clavaris-lang-switch\" aria-label=\"")
        .append(HtmlEscape.escapeHtml4Xml(label))
        .append("\">");
    for (final Locale locale : AppLocales.SUPPORTED) {
      final String name = autonym(locale);
      final boolean active = locale.getLanguage().equals(current.getLanguage());
      html.append("<a href=\"")
          .append(HtmlEscape.escapeHtml4Xml(href(context, locale)))
          .append("\" lang=\"")
          .append(locale.getLanguage())
          .append("\" hreflang=\"")
          .append(locale.getLanguage())
          .append("\" title=\"")
          .append(HtmlEscape.escapeHtml4Xml(name))
          .append("\" aria-label=\"")
          .append(HtmlEscape.escapeHtml4Xml(name))
          .append('"')
          .append(active ? " aria-current=\"true\"" : "")
          .append('>')
          .append(locale.getLanguage().toUpperCase(Locale.ROOT))
          .append("</a>");
    }
    return html.append("</nav>").toString();
  }

  /** "English", "Español": a language is always named in itself. */
  /* default */ static String autonym(final Locale locale) {
    final String name = locale.getDisplayLanguage(locale);
    return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
  }

  // The page being viewed, with its other query parameters, and the language to switch to.
  private static String href(final ITemplateContext context, final Locale target) {
    final StringBuilder href = new StringBuilder();
    if (context instanceof IWebContext web) {
      final IWebRequest request = web.getExchange().getRequest();
      href.append(request.getApplicationPath()).append(request.getRequestPath());
      href.append('?');
      for (final Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
        if (!PARAMETER.equals(parameter.getKey())) {
          for (final String value : parameter.getValue()) {
            href.append(encode(parameter.getKey())).append('=').append(encode(value)).append('&');
          }
        }
      }
    } else {
      href.append('?');
    }
    return href.append(PARAMETER).append('=').append(target.getLanguage()).toString();
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
