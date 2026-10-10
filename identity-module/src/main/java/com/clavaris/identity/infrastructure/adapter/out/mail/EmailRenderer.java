package com.clavaris.identity.infrastructure.adapter.out.mail;

import java.util.Locale;

/**
 * Lays an {@link EmailContent} out as the Clavaris email: a centred 560px card on a warm canvas,
 * the product's amber accent, one primary action, and a plain-text twin for every email.
 *
 * <p>Built for mail clients, not browsers: nested tables, inline styles, no images (so nothing is
 * blocked or tracked and the email reads the same with images off), system fonts, and a small
 * stylesheet for the clients that honour it (see {@link EmailStyle}). The blocks themselves are
 * laid out by {@link EmailBlocks}; this class is the frame around them.
 */
final class EmailRenderer {

  private static final String CENTRED = " align=\"center\"";

  private EmailRenderer() {
    // Static helpers only.
  }

  /**
   * The complete HTML document for {@code content}, sent in the name of {@code brand} (or of nobody
   * when it is {@code null} or blank), in the given language.
   */
  /* default */ static String html(
      final EmailContent content, final String brand, final String language) {
    final String name = named(brand);
    final StringBuilder body = new StringBuilder();
    content.blocks().forEach(block -> body.append(EmailBlocks.html(block)));
    final String canvas = "background:" + EmailStyle.CANVAS + ";";
    final String frame =
        EmailHtml.table(
            "560",
            "",
            "width:100%;max-width:560px;",
            wordmark(name) + card(content.title(), body.toString()) + footer(name));
    final String page =
        EmailHtml.table(
            "100%",
            EmailHtml.cls("bg"),
            canvas,
            EmailHtml.row(
                EmailHtml.element(
                    "td", EmailHtml.cls("frame") + CENTRED, "padding:40px 16px;", frame)));
    return "<!doctype html><html lang=\""
        + EmailHtml.esc(language)
        + "\">"
        + head(content.title())
        + EmailHtml.element(
            "body",
            EmailHtml.cls("bg"),
            "margin:0;padding:0;" + canvas,
            preheader(content.preheader()) + page)
        + "</html>";
  }

  /** The plain-text version: the same title, blocks and footer, with no markup. */
  /* default */ static String text(final EmailContent content, final String brand) {
    final String name = named(brand);
    final StringBuilder text = new StringBuilder();
    if (name != null) {
      text.append(name.toUpperCase(Locale.ROOT)).append("\n\n");
    }
    text.append(content.title()).append('\n');
    content
        .blocks()
        .forEach(block -> text.append('\n').append(EmailBlocks.text(block)).append('\n'));
    return text.append("\n--\n").append(footerText(name)).append('\n').toString();
  }

  private static String head(final String title) {
    return "<head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<meta name=\"color-scheme\" content=\"light dark\">"
        + "<meta name=\"supported-color-schemes\" content=\"light dark\">"
        + EmailHtml.element("title", "", "", EmailHtml.esc(title))
        + "<style>"
        + EmailStyle.STYLESHEET
        + "</style></head>";
  }

  // The padding of invisible characters keeps the first words of the body from being appended to
  // the preview line in clients that show a long one.
  private static String preheader(final String text) {
    return EmailHtml.element(
        "div",
        "",
        "display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;",
        EmailHtml.esc(text) + "&zwnj;&nbsp;".repeat(40));
  }

  // The name the email is sent in, with the small amber dot the console uses as its eyebrow mark.
  // With no name there is nothing to show: never a fallback to Clavaris on a tenant's email.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String wordmark(final String brand) {
    if (brand == null) {
      return "";
    }
    final String circle =
        EmailHtml.element(
            "div",
            "",
            "width:10px;height:10px;background:"
                + EmailStyle.AMBER
                + ";border-radius:5px;font-size:0;line-height:0;",
            "&nbsp;");
    final String dot = EmailHtml.element("td", " width=\"10\" valign=\"middle\"", "", circle);
    final String name =
        EmailHtml.element(
            "td",
            EmailHtml.cls("ink") + " valign=\"middle\"",
            "padding-left:10px;font-family:"
                + EmailStyle.SANS
                + ";font-size:17px;font-weight:600;letter-spacing:-0.02em;color:"
                + EmailStyle.INK
                + ";",
            EmailHtml.esc(brand));
    return EmailHtml.row(
        EmailHtml.element(
            "td",
            "",
            "padding:0 4px 18px;",
            EmailHtml.table("", "", "", EmailHtml.row(dot + name))));
  }

  private static String card(final String title, final String body) {
    final String heading =
        EmailHtml.element(
            "h1",
            EmailHtml.cls("ink"),
            "margin:0 0 18px;font-size:22px;line-height:1.3;font-weight:600;"
                + "letter-spacing:-0.02em;color:"
                + EmailStyle.INK
                + ";",
            EmailHtml.esc(title));
    final String padded =
        EmailHtml.element(
            "td",
            EmailHtml.cls("pad"),
            "padding:36px 40px 32px;font-family:" + EmailStyle.SANS + ";",
            heading + body);
    return EmailHtml.row(
        EmailHtml.element(
            "td",
            EmailHtml.cls("card"),
            "background:"
                + EmailStyle.SURFACE
                + ";border:"
                + EmailStyle.RULE
                + ";border-top:3px solid "
                + EmailStyle.AMBER
                + ";border-radius:14px;",
            EmailHtml.table("100%", "", "", EmailHtml.row(padded))));
  }

  // A blank name is no name: the email then names nobody, never Clavaris.
  private static String named(final String brand) {
    return brand == null || brand.isBlank() ? null : brand.strip();
  }

  private static String footerText(final String brand) {
    return brand == null
        ? EmailCopy.text(EmailCopy.FOOTER_PLAIN)
        : EmailCopy.text(EmailCopy.FOOTER, brand);
  }

  private static String footer(final String brand) {
    return EmailHtml.row(
        EmailHtml.element(
            "td",
            EmailHtml.cls("muted"),
            "padding:20px 4px 0;font-family:"
                + EmailStyle.SANS
                + ";font-size:12px;line-height:1.6;color:"
                + EmailStyle.MUTED
                + ";",
            EmailHtml.esc(footerText(brand))));
  }
}
