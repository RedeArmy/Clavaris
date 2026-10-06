package com.clavaris.common.i18n;

import com.clavaris.common.i18n.PoParser.PoEntry;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * One language's translations, keyed by the English source text.
 *
 * <p>The source text, not an invented key, is the identifier (the gettext approach): a template
 * keeps reading as the page it renders, a missing translation falls back to readable English, and
 * extraction tooling can list exactly what needs translating. An entry may also carry a context (an
 * HTML element name such as {@code th} or {@code button}) so the same English word can be rendered
 * differently where it plays a different role, and may contain numbered placeholders ({@code "{0}
 * scopes"}) that match runtime values such as counts, names and dates.
 *
 * <p>A placeholder written {@code {0:t}} captures text that is itself interface wording ({@code
 * "Copy {0:t}"} matching "Copy Client ID"), so the captured part is translated too; a plain {@code
 * {0}} is left exactly as it came, which is what keeps user data (names, emails, ids) from ever
 * being translated.
 *
 * <p>Inline markup inside a sentence appears as numbered tags ({@code "Removes <0>{1}</0> from this
 * role"}) so a translation can reorder the words around it.
 *
 * <p>Lookups try the exact text in the element's context, then the exact text anywhere, then the
 * placeholder patterns, the most specific first.
 */
public final class MessageCatalog {

  private static final String CONTEXT_SEPARATOR = "\u0004";
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)(:t)?}");
  private static final Pattern SLOT = Pattern.compile("\\{(\\d+)}");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final MessageCatalog NONE = new MessageCatalog(Map.of(), List.of(), Set.of());

  private final Map<String, String> exact;
  private final List<TranslationPattern> patterns;
  private final Set<String> blankSources;

  private MessageCatalog(
      final Map<String, String> exact,
      final List<TranslationPattern> patterns,
      final Set<String> blankSources) {
    this.exact = exact;
    this.patterns = patterns;
    this.blankSources = blankSources;
  }

  /** A catalogue with no translations: every lookup misses, so pages stay in English. */
  public static MessageCatalog empty() {
    return NONE;
  }

  public static MessageCatalog fromEntries(final List<PoEntry> entries) {
    final Map<String, String> exact = new HashMap<>();
    final List<TranslationPattern> patterns = new ArrayList<>();
    final Set<String> blank = new HashSet<>();
    for (final PoEntry entry : entries) {
      final String source = normalize(entry.msgid());
      if (entry.msgstr().isBlank()) {
        blank.add(source);
      } else if (PLACEHOLDER.matcher(source).find()) {
        patterns.add(TranslationPattern.compile(entry.context(), source, entry.msgstr()));
      } else {
        exact.put(key(entry.context(), source), entry.msgstr());
      }
    }
    patterns.sort(Comparator.comparingInt(TranslationPattern::specificity).reversed());
    return new MessageCatalog(Map.copyOf(exact), List.copyOf(patterns), Set.copyOf(blank));
  }

  /** Loads every {@code i18n/messages_<language>*.po} on the classpath, from all modules. */
  public static MessageCatalog load(final String language) {
    final List<PoEntry> entries = new ArrayList<>();
    try {
      final Resource[] files =
          new PathMatchingResourcePatternResolver()
              .getResources("classpath*:i18n/messages_" + language + "*.po");
      for (final Resource file : files) {
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
          entries.addAll(PoParser.parse(reader));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not load the " + language + " catalogue", e);
    }
    return fromEntries(entries);
  }

  /**
   * @param context the enclosing HTML element's name, or an empty string
   * @param text the source text, with any inline markup as numbered tags
   * @return the translation, with placeholders filled in, or empty if the text is not catalogued
   */
  public Optional<String> translate(final String context, final String text) {
    final String normalized = normalize(text);
    String translated = exact.get(key(context, normalized));
    if (translated == null) {
      translated = exact.get(key("", normalized));
    }
    if (translated == null) {
      translated = matchPatterns(context, normalized);
    }
    return Optional.ofNullable(translated);
  }

  private String matchPatterns(final String context, final String text) {
    String translated = null;
    for (final TranslationPattern pattern : patterns) {
      if (pattern.appliesTo(context)) {
        translated = pattern.translate(text, captured -> translate("", captured));
        if (translated != null) {
          break;
        }
      }
    }
    return translated;
  }

  /** Source texts that have an entry with no translation yet. */
  public Set<String> untranslated() {
    return blankSources;
  }

  /** Every source text that has a translation (exact entries first, then patterns). */
  public Set<String> translatedSources() {
    final Set<String> sources = new HashSet<>();
    exact.keySet().forEach(k -> sources.add(k.substring(k.indexOf(CONTEXT_SEPARATOR) + 1)));
    patterns.forEach(p -> sources.add(p.source()));
    return sources;
  }

  public int size() {
    return exact.size() + patterns.size();
  }

  /** Collapses runs of whitespace, as the segmenter does, so layout never affects a lookup. */
  public static String normalize(final String text) {
    return WHITESPACE.matcher(text).replaceAll(" ").strip();
  }

  private static String key(final String context, final String text) {
    return context + CONTEXT_SEPARATOR + text;
  }

  /** A source text with {@code {n}} placeholders, compiled to a regular expression. */
  private record TranslationPattern(
      String context,
      String source,
      Pattern regex,
      String target,
      int specificity,
      Set<String> translatable) {

    /* default */ static TranslationPattern compile(
        final String context, final String source, final String target) {
      final StringBuilder regex = new StringBuilder("^");
      final Set<String> translatable = new HashSet<>();
      final Matcher matcher = PLACEHOLDER.matcher(source);
      int last = 0;
      int literal = 0;
      while (matcher.find()) {
        final String before = source.substring(last, matcher.start());
        regex.append(Pattern.quote(before)).append("(?<g").append(matcher.group(1)).append(">.+?)");
        if (matcher.group(2) != null) {
          translatable.add(matcher.group(1));
        }
        literal += before.length();
        last = matcher.end();
      }
      final String tail = source.substring(last);
      regex.append(Pattern.quote(tail)).append('$');
      literal += tail.length();
      return new TranslationPattern(
          context,
          source,
          Pattern.compile(regex.toString(), Pattern.DOTALL),
          target,
          literal,
          Set.copyOf(translatable));
    }

    /* default */ boolean appliesTo(final String requested) {
      return context.isEmpty() || context.equals(requested);
    }

    /**
     * @param nested translates a {@code {n:t}} capture; its result is used when there is one
     */
    /* default */ String translate(
        final String text, final java.util.function.Function<String, Optional<String>> nested) {
      final Matcher matcher = regex.matcher(text);
      String result = null;
      if (matcher.matches()) {
        final Matcher slots = SLOT.matcher(target);
        final StringBuilder out = new StringBuilder();
        while (slots.find()) {
          final String value = matcher.group("g" + slots.group(1));
          final String filled =
              translatable.contains(slots.group(1)) ? nested.apply(value).orElse(value) : value;
          slots.appendReplacement(out, Matcher.quoteReplacement(filled));
        }
        slots.appendTail(out);
        result = out.toString();
      }
      return result;
    }
  }
}
