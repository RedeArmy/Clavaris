package com.clavaris.identity.infrastructure.adapter.out.mail;

import com.clavaris.identity.infrastructure.adapter.out.mail.EmailContent.Action;
import com.clavaris.identity.infrastructure.adapter.out.mail.EmailContent.Block;
import com.clavaris.identity.infrastructure.adapter.out.mail.EmailContent.Code;
import com.clavaris.identity.infrastructure.adapter.out.mail.EmailContent.Detail;
import com.clavaris.identity.infrastructure.adapter.out.mail.EmailContent.Details;
import com.clavaris.identity.infrastructure.adapter.out.mail.EmailContent.Note;
import com.clavaris.identity.infrastructure.adapter.out.mail.EmailContent.Paragraph;
import com.clavaris.identity.infrastructure.adapter.out.mail.EmailContent.Tone;
import java.util.List;

/**
 * Lays out each kind of {@link Block} as HTML for a mail client, and as the line of plain text that
 * says the same thing. Every value that is not wording the server wrote itself passes through
 * {@link EmailHtml#esc}.
 */
final class EmailBlocks {

  private static final String CELL_FONT = "font-family:" + EmailStyle.SANS + ";";
  private static final String MUTED_CLASS = EmailHtml.cls("muted");

  private EmailBlocks() {
    // Static helpers only.
  }

  /* default */ static String html(final Block block) {
    return switch (block) {
      case Paragraph paragraph -> paragraph(paragraph);
      case Action action -> action(action);
      case Code code -> code(code);
      case Details details -> details(details);
      case Note note -> note(note);
    };
  }

  /* default */ static String text(final Block block) {
    return switch (block) {
      case Paragraph(String text) -> text;
      case Action(String label, String url, Tone _) -> label + ": " + url;
      case Code(String label, String value) -> label + ": " + value;
      case Details(List<Detail> rows) ->
          String.join("\n", rows.stream().map(row -> row.label() + ": " + row.value()).toList());
      case Note(String text) -> text;
    };
  }

  private static String paragraph(final Paragraph paragraph) {
    return EmailHtml.element(
        "p",
        EmailHtml.cls("ink"),
        "margin:0 0 16px;font-size:15px;line-height:1.6;color:" + EmailStyle.INK + ";",
        EmailHtml.esc(paragraph.text()));
  }

  // The button is a table cell around a link, the form every mail client renders; the plain link
  // follows it for clients that strip buttons or readers who would rather copy it.
  private static String action(final Action action) {
    final boolean danger = action.tone() == Tone.DANGER;
    final String fill = danger ? EmailStyle.DANGER : EmailStyle.AMBER;
    final String ink = danger ? EmailStyle.WHITE : EmailStyle.INK;
    final String link =
        EmailHtml.element(
            "a",
            " href=\"" + EmailHtml.esc(action.url()) + "\"",
            "display:inline-block;padding:14px 26px;"
                + CELL_FONT
                + "font-size:15px;font-weight:600;line-height:1;color:"
                + ink
                + ";text-decoration:none;border-radius:9px;",
            EmailHtml.esc(action.label()));
    final String cell =
        EmailHtml.element(
            "td",
            " bgcolor=\"" + fill + "\"",
            "background:" + fill + ";border-radius:9px;text-align:center;",
            link);
    final String small =
        "margin:0 0 4px;font-size:12px;line-height:1.6;color:" + EmailStyle.MUTED + ";";
    return EmailHtml.table("", EmailHtml.cls("btn"), "margin:24px 0 20px;", EmailHtml.row(cell))
        + EmailHtml.element(
            "p", MUTED_CLASS, small, EmailHtml.esc(EmailCopy.text(EmailCopy.FALLBACK)))
        + EmailHtml.element(
            "p", MUTED_CLASS, small + "word-break:break-all;", EmailHtml.esc(action.url()));
  }

  private static String code(final Code code) {
    final String label =
        EmailHtml.element(
            "div",
            MUTED_CLASS,
            CELL_FONT
                + "font-size:12px;font-weight:600;letter-spacing:0.08em;text-transform:uppercase;"
                + "color:"
                + EmailStyle.MUTED
                + ";margin-bottom:10px;",
            EmailHtml.esc(code.label()));
    final String value =
        EmailHtml.element(
            "div",
            EmailHtml.cls("ink"),
            "font-family:"
                + EmailStyle.MONO
                + ";font-size:32px;font-weight:600;line-height:1.1;letter-spacing:0.3em;"
                + "padding-left:0.3em;color:"
                + EmailStyle.INK
                + ";",
            EmailHtml.esc(code.value()));
    final String cell =
        EmailHtml.element(
            "td",
            EmailHtml.cls("well") + " align=\"center\"",
            "background:"
                + EmailStyle.SUBTLE
                + ";border:"
                + EmailStyle.RULE
                + ";"
                + EmailStyle.ROUNDED
                + "padding:20px 16px;",
            label + value);
    return EmailHtml.table("100%", "", "margin:8px 0 20px;", EmailHtml.row(cell));
  }

  private static String details(final Details details) {
    final StringBuilder rows = new StringBuilder();
    final List<Detail> all = details.rows();
    for (int index = 0; index < all.size(); index++) {
      rows.append(detail(all.get(index), index < all.size() - 1));
    }
    return EmailHtml.table(
        "100%",
        EmailHtml.cls("well"),
        "margin:8px 0 20px;background:"
            + EmailStyle.SUBTLE
            + ";border:"
            + EmailStyle.RULE
            + ";"
            + EmailStyle.ROUNDED,
        rows.toString());
  }

  private static String detail(final Detail detail, final boolean ruled) {
    final String rule = ruled ? "border-bottom:" + EmailStyle.RULE + ";" : "";
    final String label =
        EmailHtml.element(
            "td",
            EmailHtml.cls("muted rule") + " width=\"110\" valign=\"top\"",
            "width:110px;padding:11px 14px;"
                + CELL_FONT
                + "font-size:13px;line-height:1.5;color:"
                + EmailStyle.MUTED
                + ";"
                + rule,
            EmailHtml.esc(detail.label()));
    final String value =
        EmailHtml.element(
            "td",
            EmailHtml.cls("ink rule") + " valign=\"top\"",
            "padding:11px 14px;"
                + CELL_FONT
                + "font-size:14px;line-height:1.5;color:"
                + EmailStyle.INK
                + ";word-break:break-word;"
                + rule,
            EmailHtml.esc(detail.value()));
    return EmailHtml.row(label + value);
  }

  private static String note(final Note note) {
    final String cell =
        EmailHtml.element(
            "td",
            EmailHtml.cls("muted rule"),
            "padding-top:18px;border-top:"
                + EmailStyle.RULE
                + ";"
                + CELL_FONT
                + "font-size:13px;line-height:1.6;color:"
                + EmailStyle.MUTED
                + ";",
            EmailHtml.esc(note.text()));
    return EmailHtml.table("100%", "", "margin-top:12px;", EmailHtml.row(cell));
  }
}
