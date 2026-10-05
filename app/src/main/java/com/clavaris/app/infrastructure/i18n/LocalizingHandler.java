package com.clavaris.app.infrastructure.i18n;

import com.clavaris.app.infrastructure.i18n.LocalizationDialect.ExtractedUnit;
import com.clavaris.common.i18n.AppLocales;
import com.clavaris.common.i18n.MessageCatalog;
import com.clavaris.common.i18n.MessageCatalogs;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AbstractTemplateHandler;
import org.thymeleaf.model.ICDATASection;
import org.thymeleaf.model.ICloseElementTag;
import org.thymeleaf.model.IComment;
import org.thymeleaf.model.IDocType;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.model.IOpenElementTag;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.model.IProcessingInstruction;
import org.thymeleaf.model.IStandaloneElementTag;
import org.thymeleaf.model.ITemplateEnd;
import org.thymeleaf.model.ITemplateEvent;
import org.thymeleaf.model.IText;
import org.thymeleaf.model.IXMLDeclaration;

/**
 * The streaming half of {@link LocalizationDialect}: collects runs of inline content between block
 * boundaries, hands each run to {@link RunTranslator} and passes everything else through. See the
 * dialect's own Javadoc for the model; the details that matter here:
 *
 * <ul>
 *   <li>Inline elements ({@code strong}, {@code code}, {@code a}, {@code span}, ...) stay inside
 *       the unit; every other element is a boundary, so a heading, a table cell, a button and a
 *       list item are each their own unit.
 *   <li>Content under {@code script}, {@code style}, {@code textarea} or an element marked {@code
 *       translate="no"} (user data: names, emails, ids) is never touched.
 *   <li>{@code <html lang>} follows the language, and every full page ends with the language
 *       switcher (except a modal login page).
 * </ul>
 */
// PMD.LawOfDemeter / CouplingBetweenObjects / TooManyMethods /
// CyclomaticComplexity
// / GodClass: a Thymeleaf template handler is by nature a switch over the template-event types,
// each
// of which is a distinct interface, and it must read the engine's context to find its
// configuration.
// The translation logic itself lives in RunTranslator, RunUnit and AttributeLocalizer.
@SuppressWarnings({
  "PMD.LawOfDemeter",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods",
  "PMD.GodClass"
})
public final class LocalizingHandler extends AbstractTemplateHandler {

  private static final Set<String> INLINE =
      Set.of(
          "a", "abbr", "b", "bdi", "code", "em", "i", "kbd", "mark", "small", "span", "strong",
          "sub", "sup", "time", "u");
  private static final Set<String> NEVER_TRANSLATED = Set.of("script", "style", "textarea");
  private static final String LINE_BREAK = "br";
  private static final String TEXTAREA = "textarea";
  private static final String BODY = "body";
  private static final String HTML = "html";
  private static final String DO_NOT_TRANSLATE = "no";

  private final List<ITemplateEvent> run = new ArrayList<>();
  private final Deque<String> boundaries = new ArrayDeque<>();
  private final Deque<Boolean> skipFrames = new ArrayDeque<>();
  private final IconCollector icon = new IconCollector();
  private int inlineDepth;
  private int skipping;
  private boolean modalPage;
  private Locale locale = AppLocales.ENGLISH;
  private MessageCatalog catalog = MessageCatalog.empty();
  private Consumer<ExtractedUnit> extractor;
  private IModelFactory factory;
  private ITemplateContext templateContext;
  private RunTranslator runs;
  private AttributeLocalizer attributes;

  // PMD.UnnecessaryConstructor: Thymeleaf instantiates the handler reflectively, and
  // AtLeastOneConstructor asks for one; the two rules contradict each other.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public LocalizingHandler() {
    super();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void setContext(final ITemplateContext context) {
    super.setContext(context);
    templateContext = context;
    factory = context.getModelFactory();
    locale = AppLocales.resolve(context.getLocale());
    final Map<String, Object> configured = context.getConfiguration().getExecutionAttributes();
    final Function<Locale, MessageCatalog> catalogs =
        (Function<Locale, MessageCatalog>) configured.get(LocalizationDialect.CATALOGS);
    catalog = catalogs == null ? MessageCatalogs.forLocale(locale) : catalogs.apply(locale);
    extractor = (Consumer<ExtractedUnit>) configured.get(LocalizationDialect.EXTRACTOR);
    runs = new RunTranslator(catalog, factory);
    attributes = new AttributeLocalizer(catalog, factory, extractor);
  }

  // --- text and inline elements gather into the current run ---------------------------------

  @Override
  public void handleText(final IText text) {
    if (icon.collecting()) {
      icon.add(text);
    } else if (skipping == 0) {
      run.add(text);
    } else {
      super.handleText(text);
    }
  }

  @Override
  public void handleOpenElement(final IOpenElementTag tag) {
    final String name = nameOf(tag);
    if (skipping == 0 && icon.takes(tag)) {
      icon.open(tag);
    } else if (skipping == 0 && INLINE.contains(name)) {
      run.add(tag);
      inlineDepth++;
    } else {
      openBlock(name, tag);
    }
  }

  private void openBlock(final String name, final IOpenElementTag tag) {
    flushRun();
    boundaries.push(name);
    final boolean outside = skipping == 0;
    final boolean skip = !outside || startsSkippedBlock(name, tag);
    skipFrames.push(skip);
    if (skip) {
      skipping++;
    }
    if (BODY.equals(name)) {
      modalPage = tag.hasAttribute("data-modal");
    }
    // A textarea's body is what the person typed and is never translated, but its own attributes
    // (the placeholder) are wording like any other.
    final boolean wording = outside && (!skip || TEXTAREA.equals(name));
    super.handleOpenElement(wording ? prepare(name, tag) : tag);
  }

  @Override
  public void handleCloseElement(final ICloseElementTag tag) {
    final String name = nameOf(tag);
    if (icon.collecting()) {
      icon.close(tag, factory, run);
    } else if (skipping == 0 && INLINE.contains(name) && inlineDepth > 0) {
      run.add(tag);
      inlineDepth--;
    } else {
      closeBlock(name, tag);
    }
  }

  private void closeBlock(final String name, final ICloseElementTag tag) {
    flushRun();
    if (!boundaries.isEmpty()) {
      boundaries.pop();
    }
    if (!skipFrames.isEmpty() && skipFrames.pop()) {
      skipping--;
    }
    if (BODY.equals(name) && skipping == 0 && extractor == null && !modalPage) {
      super.handleText(
          factory.createText(LanguageSwitcher.markup(locale, catalog, templateContext)));
    }
    super.handleCloseElement(tag);
  }

  @Override
  public void handleStandaloneElement(final IStandaloneElementTag tag) {
    if (icon.collecting()) {
      icon.add(tag);
    } else if (skipping == 0 && LINE_BREAK.equals(nameOf(tag))) {
      run.add(tag);
    } else {
      flushRun();
      super.handleStandaloneElement(skipping > 0 ? tag : attributes.apply(tag));
    }
  }

  // --- everything else ends the run and passes through --------------------------------------

  @Override
  public void handleComment(final IComment comment) {
    flushRun();
    super.handleComment(comment);
  }

  @Override
  public void handleCDATASection(final ICDATASection section) {
    flushRun();
    super.handleCDATASection(section);
  }

  @Override
  public void handleDocType(final IDocType docType) {
    flushRun();
    super.handleDocType(docType);
  }

  @Override
  public void handleXMLDeclaration(final IXMLDeclaration declaration) {
    flushRun();
    super.handleXMLDeclaration(declaration);
  }

  @Override
  public void handleProcessingInstruction(final IProcessingInstruction instruction) {
    flushRun();
    super.handleProcessingInstruction(instruction);
  }

  @Override
  public void handleTemplateEnd(final ITemplateEnd templateEnd) {
    flushRun();
    super.handleTemplateEnd(templateEnd);
  }

  // --- the run ---------------------------------------------------------------------------

  private void flushRun() {
    if (!run.isEmpty()) {
      final List<ITemplateEvent> events = new ArrayList<>(run);
      run.clear();
      inlineDepth = 0;
      final String context = boundaries.isEmpty() ? "" : boundaries.peek();
      emit(extractor == null ? runs.translate(events, context) : extract(events, context));
    }
  }

  // Lists the units instead of translating, using the same division into units as translation.
  private List<ITemplateEvent> extract(final List<ITemplateEvent> events, final String context) {
    return RunTranslator.walk(
        events,
        context,
        (leaf, leafContext) -> {
          final RunUnit unit = RunUnit.parse(leaf, true);
          if (unit.hasWords()) {
            // A unit that cannot be translated (an icon or stray tag mid-sentence) is reported
            // under its own context, so it shows up as a gap instead of staying English silently.
            extractor.accept(
                new ExtractedUnit(unit.isBalanced() ? leafContext : "untranslatable", unit.key()));
          }
          return leaf;
        },
        tag -> tag.hasAttribute("th:text") || tag.hasAttribute("th:utext"));
  }

  private void emit(final List<ITemplateEvent> events) {
    for (final ITemplateEvent event : events) {
      if (event instanceof IText text) {
        super.handleText(text);
      } else if (event instanceof IOpenElementTag tag) {
        super.handleOpenElement(attributes.apply(tag));
      } else if (event instanceof ICloseElementTag tag) {
        super.handleCloseElement(tag);
      } else if (event instanceof IStandaloneElementTag tag) {
        super.handleStandaloneElement(attributes.apply(tag));
      }
    }
  }

  // --- elements ----------------------------------------------------------------------------

  private boolean startsSkippedBlock(final String name, final IOpenElementTag tag) {
    return NEVER_TRANSLATED.contains(name)
        || DO_NOT_TRANSLATE.equals(tag.getAttributeValue("translate"))
        || (extractor != null && (tag.hasAttribute("th:text") || tag.hasAttribute("th:utext")));
  }

  private IOpenElementTag prepare(final String name, final IOpenElementTag tag) {
    IOpenElementTag prepared = attributes.apply(tag);
    if (HTML.equals(name) && extractor == null) {
      prepared = factory.setAttribute(prepared, "lang", locale.getLanguage());
    }
    return prepared;
  }

  private static String nameOf(final IProcessableElementTag tag) {
    return tag.getElementCompleteName().toLowerCase(Locale.ROOT);
  }

  private static String nameOf(final ICloseElementTag tag) {
    return tag.getElementCompleteName().toLowerCase(Locale.ROOT);
  }
}
