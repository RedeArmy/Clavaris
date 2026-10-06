package com.clavaris.app.infrastructure.i18n;

import java.util.List;
import org.thymeleaf.model.ICloseElementTag;
import org.thymeleaf.model.IOpenElementTag;
import org.thymeleaf.model.ITemplateEvent;
import org.thymeleaf.model.IText;

/**
 * Finds the icons at the two ends of a run.
 *
 * <p>An icon (an {@code <svg>} kept whole as one text event by {@link IconCollector}) is not part
 * of what is translated, so the text between the icons at the edges is the unit. An element that
 * holds nothing but an icon ({@code <span class="icon"><svg/></span>} beside a label) counts as an
 * icon too. An icon in the middle of a sentence is left alone, which makes that sentence
 * untranslatable.
 */
final class IconEdges {

  private static final String ICON_PREFIX = "<svg";

  private IconEdges() {
    // Static helpers only.
  }

  /** True for the opaque text event that {@link IconCollector} makes of an icon. */
  /* default */ static boolean isIcon(final ITemplateEvent event) {
    return event instanceof IText text && text.getText().startsWith(ICON_PREFIX);
  }

  /** The [start, end) of the events that are not icons at the edges of {@code events}. */
  /* default */ static int[] bounds(final List<ITemplateEvent> events) {
    int start = 0;
    int end = events.size();
    int next = iconEnd(events, start, end);
    while (next > start) {
      start = next;
      next = iconEnd(events, start, end);
    }
    int before = iconStart(events, start, end);
    while (before < end) {
      end = before;
      before = iconStart(events, start, end);
    }
    return new int[] {start, end};
  }

  /** The index of the close that matches the open at {@code from}, or -1. */
  /* default */ static int closeOf(final List<ITemplateEvent> events, final int from) {
    int depth = 0;
    int match = -1;
    for (int index = from; index < events.size() && match < 0; index++) {
      final ITemplateEvent event = events.get(index);
      if (event instanceof IOpenElementTag) {
        depth++;
      } else if (event instanceof ICloseElementTag) {
        depth--;
        match = depth == 0 ? index : -1;
      }
    }
    return match;
  }

  // Where the icon that begins at `from` ends (exclusive), or `from` if none begins there.
  private static int iconEnd(final List<ITemplateEvent> events, final int from, final int limit) {
    int end = from;
    if (from < limit) {
      final ITemplateEvent first = events.get(from);
      if (isIcon(first)) {
        end = from + 1;
      } else if (first instanceof IOpenElementTag) {
        end = wrapperEnd(events, from, limit);
      }
    }
    return end;
  }

  private static int wrapperEnd(
      final List<ITemplateEvent> events, final int from, final int limit) {
    final int close = closeOf(events, from);
    final boolean wrapper =
        close > from && close < limit && onlyIcons(events.subList(from + 1, close));
    return wrapper ? close + 1 : from;
  }

  // Where the icon that ends at `end` begins, or `end` if none ends there.
  private static int iconStart(final List<ITemplateEvent> events, final int low, final int end) {
    int start = end;
    if (end > low) {
      final ITemplateEvent last = events.get(end - 1);
      if (isIcon(last)) {
        start = end - 1;
      } else if (last instanceof ICloseElementTag) {
        start = wrapperStart(events, low, end);
      }
    }
    return start;
  }

  private static int wrapperStart(final List<ITemplateEvent> events, final int low, final int end) {
    int depth = 0;
    int open = -1;
    for (int index = end - 1; index >= low && open < 0; index--) {
      final ITemplateEvent event = events.get(index);
      if (event instanceof ICloseElementTag) {
        depth++;
      } else if (event instanceof IOpenElementTag) {
        depth--;
        open = depth == 0 ? index : -1;
      }
    }
    final boolean wrapper = open >= 0 && onlyIcons(events.subList(open + 1, end - 1));
    return wrapper ? open : end;
  }

  private static boolean onlyIcons(final List<ITemplateEvent> inner) {
    return inner.stream().anyMatch(IconEdges::isIcon)
        && inner.stream().allMatch(event -> isIcon(event) || isBlank(event));
  }

  private static boolean isBlank(final ITemplateEvent event) {
    return event instanceof IText text && text.getText().isBlank();
  }
}
