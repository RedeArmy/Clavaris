package com.clavaris.app.infrastructure.i18n;

import com.clavaris.common.i18n.MessageCatalog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.thymeleaf.model.ICloseElementTag;
import org.thymeleaf.model.IOpenElementTag;
import org.thymeleaf.model.IStandaloneElementTag;
import org.thymeleaf.model.ITemplateEvent;
import org.thymeleaf.model.IText;
import org.unbescape.html.HtmlEscape;

/**
 * A run of inline content (text and inline elements) written as one translatable string, and the
 * means to turn a translation of that string back into template events.
 *
 * <p>The text is unescaped and each inline element becomes a numbered tag: {@code Removes
 * <0>Ada</0> from this role}. A translation may place those tags anywhere, which is how a sentence
 * whose Spanish word order differs still keeps its {@code <strong>} on the right words.
 *
 * <p>While extracting (the standard dialect has not run, so {@code th:text} is still an attribute),
 * an inline element whose body is dynamic contributes a placeholder, {@code <0>{0}</0>}, instead of
 * its prototype text.
 */
final class RunUnit {

  private static final Pattern TAG_TOKEN = Pattern.compile("<(/?)(\\d+)(/?)>");
  private static final Pattern LETTER = Pattern.compile("\\p{L}");

  private final String text;
  private final List<ITemplateEvent> opens;
  private final List<ITemplateEvent> closes;
  private final boolean balanced;

  private RunUnit(
      final String text,
      final List<ITemplateEvent> opens,
      final List<ITemplateEvent> closes,
      final boolean balanced) {
    this.text = text;
    this.opens = opens;
    this.closes = closes;
    this.balanced = balanced;
  }

  /** Builds the unit for {@code events}; {@code extracting} makes dynamic bodies placeholders. */
  /* default */ static RunUnit parse(final List<ITemplateEvent> events, final boolean extracting) {
    final Reader reader = new Reader(extracting);
    events.forEach(reader::accept);
    return reader.unit();
  }

  /* default */ String key() {
    return text;
  }

  /* default */ boolean isBalanced() {
    return balanced;
  }

  /** True when there is something to translate: at least one letter outside the markup. */
  /* default */ boolean hasWords() {
    return LETTER.matcher(TAG_TOKEN.matcher(text).replaceAll("")).find();
  }

  /**
   * Turns a translation back into events: plain text becomes escaped text, and each numbered tag
   * becomes the original element's event.
   *
   * @return empty if the translation refers to a tag the source does not have
   */
  /* default */ List<ITemplateEvent> rebuild(
      final String translation, final Function<String, ITemplateEvent> textFactory) {
    final List<ITemplateEvent> out = new ArrayList<>();
    final Matcher matcher = TAG_TOKEN.matcher(translation);
    int last = 0;
    boolean valid = true;
    while (valid && matcher.find()) {
      appendText(out, translation.substring(last, matcher.start()), textFactory);
      final int index = Integer.parseInt(matcher.group(2));
      valid = index < opens.size();
      if (valid) {
        out.add(eventFor(matcher.group(1), matcher.group(3), index));
      }
      last = matcher.end();
    }
    appendText(out, translation.substring(last), textFactory);
    return valid ? out : List.of();
  }

  // <n> opens, </n> closes, <n/> stands alone.
  private ITemplateEvent eventFor(final String closing, final String standalone, final int index) {
    final boolean closesElement = !closing.isEmpty() && standalone.isEmpty();
    return closesElement ? closes.get(index) : opens.get(index);
  }

  private static void appendText(
      final List<ITemplateEvent> out,
      final String piece,
      final Function<String, ITemplateEvent> textFactory) {
    if (!piece.isEmpty()) {
      out.add(textFactory.apply(HtmlEscape.escapeHtml4Xml(piece)));
    }
  }

  /** Reads the events of one run and accumulates its string and its tags. */
  private static final class Reader {
    private static final int OUTERMOST = 1;

    private final boolean extracting;
    private final List<String> parts = new ArrayList<>();
    private final List<ITemplateEvent> opens = new ArrayList<>();
    private final List<ITemplateEvent> closes = new ArrayList<>();
    private final Deque<Integer> open = new ArrayDeque<>();
    private int dynamicDepth;
    private int placeholders;
    private boolean balanced = true;

    /* default */ Reader(final boolean extracting) {
      this.extracting = extracting;
    }

    /* default */ void accept(final ITemplateEvent event) {
      if (event instanceof IText plain) {
        onText(plain);
      } else if (event instanceof IOpenElementTag tag) {
        onOpen(tag);
      } else if (event instanceof ICloseElementTag tag) {
        onClose(tag);
      } else if (event instanceof IStandaloneElementTag tag) {
        onStandalone(tag);
      }
    }

    /* default */ RunUnit unit() {
      return new RunUnit(
          MessageCatalog.normalize(String.join("", parts)),
          opens,
          closes,
          balanced && open.isEmpty());
    }

    private void onText(final IText plain) {
      if (IconEdges.isIcon(plain)) {
        balanced = false;
      } else if (dynamicDepth == 0) {
        parts.add(HtmlEscape.unescapeHtml(plain.getText()));
      }
    }

    private void onOpen(final IOpenElementTag tag) {
      if (dynamicDepth > 0) {
        dynamicDepth++;
      } else {
        final int index = opens.size();
        open.push(index);
        parts.add("<" + index + ">");
        opens.add(tag);
        closes.add(null);
        if (extracting && (tag.hasAttribute("th:text") || tag.hasAttribute("th:utext"))) {
          parts.add("{" + placeholders + "}");
          placeholders++;
          dynamicDepth = OUTERMOST;
        }
      }
    }

    private void onClose(final ICloseElementTag tag) {
      if (dynamicDepth > OUTERMOST) {
        dynamicDepth--;
      } else if (open.isEmpty()) {
        balanced = false;
      } else {
        dynamicDepth = 0;
        final int index = open.pop();
        closes.set(index, tag);
        parts.add("</" + index + ">");
      }
    }

    private void onStandalone(final IStandaloneElementTag tag) {
      if (dynamicDepth == 0) {
        parts.add("<" + opens.size() + "/>");
        opens.add(tag);
        closes.add(null);
      }
    }
  }
}
