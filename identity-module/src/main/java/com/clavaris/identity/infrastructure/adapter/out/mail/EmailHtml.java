package com.clavaris.identity.infrastructure.adapter.out.mail;

import org.springframework.web.util.HtmlUtils;

/**
 * The few HTML building blocks every email is made of. Attribute values passed in must already be
 * safe (the fixed styles in {@link EmailStyle}, or a value that went through {@link #esc}); text
 * content must go through {@link #esc} before it is handed to {@link #element}.
 */
final class EmailHtml {

  private EmailHtml() {
    // Static helpers only.
  }

  /**
   * {@code <tag attrs style="style">inner</tag>}; {@code attrs} is raw, e.g. {@code cls("ink")}.
   */
  /* default */ static String element(
      final String tag, final String attrs, final String style, final String inner) {
    return "<"
        + tag
        + attrs
        + (style.isEmpty() ? "" : " style=\"" + style + "\"")
        + ">"
        + inner
        + "</"
        + tag
        + ">";
  }

  /** A layout table: mail clients still lay out with tables, never with flex or grid. */
  /* default */ static String table(
      final String width, final String attrs, final String style, final String rows) {
    final String sized = width.isEmpty() ? "" : " width=\"" + width + "\"";
    return element(
        "table",
        " role=\"presentation\""
            + sized
            + " cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
            + attrs,
        style,
        rows);
  }

  /** A table row holding the given cells. */
  /* default */ static String row(final String cells) {
    return element("tr", "", "", cells);
  }

  /** A class attribute, for the stylesheet that dark mode and the phone layout rely on. */
  /* default */ static String cls(final String names) {
    return " class=\"" + names + "\"";
  }

  /** HTML-escapes text or an attribute value. */
  /* default */ static String esc(final String value) {
    return HtmlUtils.htmlEscape(value);
  }
}
