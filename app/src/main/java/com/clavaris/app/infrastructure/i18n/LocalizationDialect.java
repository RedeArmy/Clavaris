package com.clavaris.app.infrastructure.i18n;

import com.clavaris.common.i18n.MessageCatalog;
import com.clavaris.common.i18n.MessageCatalogs;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExecutionAttributeDialect;
import org.thymeleaf.dialect.IPostProcessorDialect;
import org.thymeleaf.postprocessor.IPostProcessor;
import org.thymeleaf.postprocessor.PostProcessor;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Translates every rendered page into the reader's language.
 *
 * <p>Templates are written once, in English. After Thymeleaf has produced a page, this dialect's
 * handler splits it into <em>units</em> (a sentence, a label, a button, a heading: the text between
 * block boundaries, with inline markup such as {@code <strong>} kept as numbered tags), looks each
 * unit up in the language's catalogue and, on a hit, emits the translation in its place. A unit
 * with no translation is left exactly as it was, so a missing entry degrades to English, never to a
 * blank or a key. Attributes that carry words ({@code title}, {@code placeholder}, {@code
 * aria-label}, the confirmation texts) are translated the same way, {@code <html lang>} follows the
 * language, and a language switcher is added to every full page.
 *
 * <p>Because translation happens on the output, it also reaches text that Java code builds (audit
 * log sentences, validation messages, relative times) without those classes knowing about
 * languages: a placeholder entry such as {@code "{0} hours ago"} matches the runtime value.
 *
 * <p>Two execution attributes configure the engine without touching the handler class, which
 * Thymeleaf instantiates itself: {@link #CATALOGS} (where translations come from) and {@link
 * #EXTRACTOR} (a sink that, when present, receives every unit instead of translating: used to list
 * what a catalogue must cover).
 */
public final class LocalizationDialect extends AbstractDialect
    implements IPostProcessorDialect, IExecutionAttributeDialect {

  /** Execution attribute holding a {@code Function<Locale, MessageCatalog>}. */
  public static final String CATALOGS = "clavaris.i18n.catalogs";

  /**
   * Execution attribute holding a {@code Consumer<ExtractedUnit>}; presence switches to extraction.
   */
  public static final String EXTRACTOR = "clavaris.i18n.extractor";

  private static final String NAME = "Clavaris localisation";
  // After the standard processors and the comment stripper, so it sees the final page.
  private static final int PRECEDENCE = 1100;

  private final Function<Locale, MessageCatalog> catalogs;
  private final Consumer<ExtractedUnit> extractor;

  /** The normal configuration: catalogues from the classpath. */
  public LocalizationDialect() {
    this(MessageCatalogs::forLocale, null);
  }

  public LocalizationDialect(
      final Function<Locale, MessageCatalog> catalogs, final Consumer<ExtractedUnit> extractor) {
    super(NAME);
    this.catalogs = catalogs;
    this.extractor = extractor;
  }

  @Override
  public int getDialectPostProcessorPrecedence() {
    return PRECEDENCE;
  }

  @Override
  public Set<IPostProcessor> getPostProcessors() {
    return Set.of(new PostProcessor(TemplateMode.HTML, LocalizingHandler.class, PRECEDENCE));
  }

  @Override
  public Map<String, Object> getExecutionAttributes() {
    return extractor == null
        ? Map.of(CATALOGS, catalogs)
        : Map.of(CATALOGS, catalogs, EXTRACTOR, extractor);
  }

  /**
   * A unit found while extracting.
   *
   * @param context the enclosing element's name
   * @param text the source text, with inline markup as numbered tags and dynamic parts as {@code
   *     {n}}
   */
  public record ExtractedUnit(String context, String text) {}
}
