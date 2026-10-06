package com.clavaris.app.infrastructure.i18n;

import com.clavaris.app.infrastructure.i18n.LocalizationDialect.ExtractedUnit;
import com.clavaris.common.i18n.MessageCatalog;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.thymeleaf.model.IAttribute;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.model.IProcessableElementTag;

/**
 * Translates the attributes that carry words ({@code title}, {@code placeholder}, {@code
 * aria-label}, the confirmation texts, ...), or, while extracting, reports them.
 *
 * <p>Each attribute is looked up under the context {@code @<name>} (so {@code "Close"} can read
 * differently as a {@code title} than as a button), then without a context.
 */
final class AttributeLocalizer {

  private static final Set<String> TRANSLATED =
      Set.of(
          "title",
          "alt",
          "placeholder",
          "aria-label",
          "aria-description",
          "data-confirm",
          "data-confirm-title",
          "data-confirm-label",
          "label",
          "summary");
  // The expressions whose string literals become visible text once the standard dialect has run.
  private static final Set<String> TH_TEXT =
      Set.of(
          "th:text",
          "th:utext",
          "th:title",
          "th:placeholder",
          "th:alt",
          "th:attr",
          "th:aria-label",
          "th:value",
          "th:label");
  private static final Pattern LITERAL = Pattern.compile("'([^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+)'");

  private final MessageCatalog catalog;
  private final IModelFactory factory;
  private final Consumer<ExtractedUnit> extractor;

  /* default */ AttributeLocalizer(
      final MessageCatalog catalog,
      final IModelFactory factory,
      final Consumer<ExtractedUnit> extractor) {
    this.catalog = catalog;
    this.factory = factory;
    this.extractor = extractor;
  }

  /** The tag with its translatable attributes translated (or reported, when extracting). */
  /* default */ <T extends IProcessableElementTag> T apply(final T tag) {
    T result = tag;
    if (extractor != null) {
      report(tag);
    } else {
      for (final String name : TRANSLATED) {
        final String value = tag.getAttributeValue(name);
        final Optional<String> translated =
            value == null || value.isBlank()
                ? Optional.empty()
                : catalog.translate("@" + name, value);
        if (translated.isPresent()) {
          result = factory.setAttribute(result, name, translated.get());
        }
      }
    }
    return result;
  }

  // The words in plain attributes, and the string literals inside th:* expressions
  // (th:text="${ok} ? 'Active' : 'Inactive'").
  private void report(final IProcessableElementTag tag) {
    for (final String name : TRANSLATED) {
      final String value = tag.getAttributeValue(name);
      if (value != null && !value.isBlank() && !value.startsWith("$")) {
        extractor.accept(new ExtractedUnit("@" + name, MessageCatalog.normalize(value)));
      }
    }
    for (final IAttribute attribute : tag.getAllAttributes()) {
      final String name = attribute.getAttributeCompleteName();
      if (TH_TEXT.contains(name) && attribute.getValue() != null) {
        final Matcher literal = LITERAL.matcher(attribute.getValue());
        while (literal.find()) {
          extractor.accept(new ExtractedUnit("literal:" + name, literal.group(1)));
        }
      }
    }
  }
}
