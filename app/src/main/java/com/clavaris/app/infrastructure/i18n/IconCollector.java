package com.clavaris.app.infrastructure.i18n;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.thymeleaf.model.ICloseElementTag;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.model.IOpenElementTag;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.model.ITemplateEvent;
import org.thymeleaf.model.IText;

/**
 * Gathers an {@code <svg>} icon, tags and all, into one opaque text event.
 *
 * <p>An icon next to a label is not part of what is translated, but it must not split the label
 * from the elements around it either; kept whole, it sits in the run as a single item that {@link
 * RunTranslator} sets aside.
 */
final class IconCollector {

  private static final String SVG = "svg";

  private final List<String> pieces = new ArrayList<>();
  private int depth;

  // PMD.UnnecessaryConstructor: AtLeastOneConstructor asks for one; the two rules contradict.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* default */ IconCollector() {
    // Gathers events one icon at a time.
  }

  /** True while inside an icon, whose events all belong to it. */
  /* default */ boolean collecting() {
    return depth > 0;
  }

  /** True if {@code tag} opens an icon, or is inside one. */
  /* default */ boolean takes(final IOpenElementTag tag) {
    return depth > 0 || SVG.equals(nameOf(tag));
  }

  /* default */ void open(final IOpenElementTag tag) {
    depth++;
    add(tag);
  }

  /* default */ void add(final ITemplateEvent event) {
    if (event instanceof IText text) {
      pieces.add(text.getText());
    } else {
      final StringWriter writer = new StringWriter();
      try {
        event.write(writer);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      pieces.add(writer.toString());
    }
  }

  /** Ends the icon that {@code tag} closes, and when that is the whole icon, adds it to the run. */
  /* default */ void close(
      final ICloseElementTag tag, final IModelFactory factory, final List<ITemplateEvent> run) {
    add(tag);
    depth--;
    if (depth == 0) {
      run.add(factory.createText(String.join("", pieces)));
      pieces.clear();
    }
  }

  private static String nameOf(final IProcessableElementTag tag) {
    return tag.getElementCompleteName().toLowerCase(Locale.ROOT);
  }
}
