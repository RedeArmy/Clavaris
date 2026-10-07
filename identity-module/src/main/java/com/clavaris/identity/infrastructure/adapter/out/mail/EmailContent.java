package com.clavaris.identity.infrastructure.adapter.out.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * What one Clavaris email says, with no layout: a title, the preview line mail apps show beside the
 * subject, and a sequence of blocks. {@link EmailRenderer} turns it into the HTML and the
 * plain-text versions, so the two can never say different things and a new email only has to
 * describe its content.
 */
final class EmailContent {

  private final String heading;
  private final String preview;
  private final List<Block> parts = new ArrayList<>();

  /** How prominent an action is: the default amber, or the red used for "lock my account". */
  /* default */ enum Tone {
    DEFAULT,
    DANGER
  }

  /** One piece of the body, in reading order. */
  /* default */ sealed interface Block permits Paragraph, Action, Code, Details, Note {}

  /* default */ record Paragraph(String text) implements Block {}

  /** A button, with the plain link kept alongside for clients that do not render it. */
  /* default */ record Action(String label, String url, Tone tone) implements Block {}

  /** A short one-time code, shown large so it is easy to read and retype. */
  /* default */ record Code(String label, String value) implements Block {}

  /* default */ record Detail(String label, String value) {}

  /** Label and value rows, such as the device and address of a sign-in. */
  /* default */ record Details(List<Detail> rows) implements Block {}

  /** The small print: expiry, what to do if it was not you. */
  /* default */ record Note(String text) implements Block {}

  private EmailContent(final String heading, final String preview) {
    this.heading = heading;
    this.preview = preview;
  }

  /* default */ static EmailContent titled(final String title, final String preheader) {
    return new EmailContent(title, preheader);
  }

  /* default */ EmailContent paragraph(final String text) {
    parts.add(new Paragraph(text));
    return this;
  }

  /* default */ EmailContent action(final String label, final String url) {
    parts.add(new Action(label, url, Tone.DEFAULT));
    return this;
  }

  /* default */ EmailContent dangerAction(final String label, final String url) {
    parts.add(new Action(label, url, Tone.DANGER));
    return this;
  }

  /* default */ EmailContent code(final String label, final String value) {
    parts.add(new Code(label, value));
    return this;
  }

  /* default */ EmailContent details(final List<Detail> rows) {
    parts.add(new Details(List.copyOf(rows)));
    return this;
  }

  /* default */ EmailContent note(final String text) {
    parts.add(new Note(text));
    return this;
  }

  /* default */ String title() {
    return heading;
  }

  /* default */ String preheader() {
    return preview;
  }

  /* default */ List<Block> blocks() {
    return List.copyOf(parts);
  }
}
