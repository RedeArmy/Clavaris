package com.clavaris.app.infrastructure.i18n;

import com.clavaris.common.i18n.MessageCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.model.IOpenElementTag;
import org.thymeleaf.model.ITemplateEvent;
import org.thymeleaf.model.IText;

/**
 * Translates one run of inline content, and knows how a run divides into units.
 *
 * <p>A run whose top level is only inline elements separated by space (a card made of several
 * {@code <span>}s, a link that wraps everything) is not one sentence: each element is translated
 * from the inside, on its own. A run with loose text among its elements ({@code Already have an
 * account? <a>Sign in</a>}) is one sentence and stays one unit, so the translation can reorder the
 * words around the markup. {@link #walk} is the one place that decides this, used both when
 * translating and when listing units, so the two can never disagree.
 */
final class RunTranslator {

  private final MessageCatalog catalog;
  private final IModelFactory factory;

  /* default */ RunTranslator(final MessageCatalog catalog, final IModelFactory factory) {
    this.catalog = catalog;
    this.factory = factory;
  }

  /** The events to emit for {@code events}: translated wherever the catalogue has the text. */
  /* default */ List<ITemplateEvent> translate(
      final List<ITemplateEvent> events, final String context) {
    return walk(events, context, this::translateLeaf, tag -> false);
  }

  /**
   * Divides a run into its units and applies {@code leaf} to each.
   *
   * @param opaque elements whose inside must be left alone (a dynamic body, while extracting)
   */
  /* default */ static List<ITemplateEvent> walk(
      final List<ITemplateEvent> events,
      final String context,
      final BiFunction<List<ITemplateEvent>, String, List<ITemplateEvent>> leaf,
      final Predicate<IOpenElementTag> opaque) {
    final List<int[]> elements = topLevelElements(events);
    return elements.isEmpty()
        ? aroundIcons(events, context, leaf)
        : divide(events, elements, leaf, opaque);
  }

  // Icons at the edges of a unit are kept where they are; the text between them is the unit.
  private static List<ITemplateEvent> aroundIcons(
      final List<ITemplateEvent> events,
      final String context,
      final BiFunction<List<ITemplateEvent>, String, List<ITemplateEvent>> leaf) {
    final int[] bounds = IconEdges.bounds(events);
    final List<ITemplateEvent> result = new ArrayList<>(events.subList(0, bounds[0]));
    result.addAll(leaf.apply(events.subList(bounds[0], bounds[1]), context));
    result.addAll(events.subList(bounds[1], events.size()));
    return result;
  }

  // Each top-level element is translated from the inside; what lies between them is kept.
  private static List<ITemplateEvent> divide(
      final List<ITemplateEvent> events,
      final List<int[]> elements,
      final BiFunction<List<ITemplateEvent>, String, List<ITemplateEvent>> leaf,
      final Predicate<IOpenElementTag> opaque) {
    final List<ITemplateEvent> result = new ArrayList<>();
    int from = 0;
    for (final int[] element : elements) {
      result.addAll(events.subList(from, element[0]));
      final IOpenElementTag open = (IOpenElementTag) events.get(element[0]);
      result.add(open);
      final List<ITemplateEvent> inner = events.subList(element[0] + 1, element[1]);
      result.addAll(opaque.test(open) ? inner : walk(inner, nameOf(open), leaf, opaque));
      result.add(events.get(element[1]));
      from = element[1] + 1;
    }
    result.addAll(events.subList(from, events.size()));
    return result;
  }

  // The [open, close] positions of each top-level element, when the run is only such elements and
  // whitespace (an unmatched tag is skipped); empty when there is any loose text (the run is one
  // sentence) or no element at all.
  private static List<int[]> topLevelElements(final List<ITemplateEvent> events) {
    final List<int[]> elements = new ArrayList<>();
    boolean container = true;
    int index = 0;
    while (container && index < events.size()) {
      final ITemplateEvent event = events.get(index);
      if (event instanceof IOpenElementTag) {
        final int close = IconEdges.closeOf(events, index);
        // An element whose close is not in this run (its inside holds a block, such as an icon)
        // is passed through; the elements around it are still translated.
        if (close > index) {
          elements.add(new int[] {index, close});
          index = close;
        }
      } else if (event instanceof IText text) {
        container = IconEdges.isIcon(text) || text.getText().isBlank();
      }
      index++;
    }
    return container ? elements : List.of();
  }

  private List<ITemplateEvent> translateLeaf(
      final List<ITemplateEvent> events, final String context) {
    final RunUnit unit = RunUnit.parse(events, false);
    List<ITemplateEvent> result = events;
    if (unit.isBalanced() && unit.hasWords()) {
      final List<ITemplateEvent> rebuilt =
          catalog
              .translate(context, unit.key())
              .map(text -> unit.rebuild(text, factory::createText))
              .orElse(List.of());
      result = withEdgeSpace(events, rebuilt);
    }
    return result;
  }

  private List<ITemplateEvent> withEdgeSpace(
      final List<ITemplateEvent> original, final List<ITemplateEvent> translated) {
    List<ITemplateEvent> result = original;
    if (!translated.isEmpty()) {
      final List<ITemplateEvent> out = new ArrayList<>();
      if (edgeIsSpace(original.get(0), true)) {
        out.add(factory.createText(" "));
      }
      out.addAll(translated);
      if (edgeIsSpace(original.get(original.size() - 1), false)) {
        out.add(factory.createText(" "));
      }
      result = out;
    }
    return result;
  }

  private static boolean edgeIsSpace(final ITemplateEvent event, final boolean leading) {
    boolean space = false;
    if (event instanceof IText text && !text.getText().isEmpty()) {
      final String content = text.getText();
      space = Character.isWhitespace(content.charAt(leading ? 0 : content.length() - 1));
    }
    return space;
  }

  private static String nameOf(final IOpenElementTag tag) {
    return tag.getElementCompleteName().toLowerCase(Locale.ROOT);
  }
}
