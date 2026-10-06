package com.clavaris.common.i18n;

import java.io.BufferedReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the subset of GNU gettext {@code .po} files the interface catalogues use: {@code msgctxt},
 * {@code msgid} and {@code msgstr}, each of which may continue over several quoted lines, plus
 * comments. Plural forms are deliberately not supported: a count that changes the wording is two
 * entries ({@code "1 scope"} and {@code "{0} scopes"}), which also lets each language word them
 * freely.
 *
 * <p>Using a standard format means translators can work in any {@code .po} editor (Poedit, Weblate,
 * Crowdin) without learning anything specific to this project.
 */
public final class PoParser {

  private static final String KEY_CONTEXT = "msgctxt";
  private static final String KEY_ID = "msgid";
  private static final String KEY_STRING = "msgstr";
  private static final String QUOTE = "\"";
  private static final String COMMENT = "#";
  private static final char BACKSLASH = '\\';

  private PoParser() {
    // Static helpers only.
  }

  /** One catalogue entry. {@code context} is empty when the entry applies everywhere. */
  public record PoEntry(String context, String msgid, String msgstr) {}

  private enum Field {
    NONE,
    CONTEXT,
    MSGID,
    TEXT
  }

  /** The entry being read, one field at a time. */
  private static final class Draft {
    private String context = "";
    private String source = "";
    private String text = "";
    private Field current = Field.NONE;
    private boolean translated;

    /* default */ void start(final Field field, final String value) {
      current = field;
      translated = translated || field == Field.TEXT;
      append(value);
    }

    /* default */ void append(final String value) {
      switch (current) {
        case CONTEXT -> context += value;
        case MSGID -> source += value;
        case TEXT -> text += value;
        default -> {
          // A quoted line before any field: nothing to continue.
        }
      }
    }

    /* default */ boolean isComplete() {
      return translated;
    }

    /* default */ PoEntry finish() {
      final PoEntry entry = new PoEntry(context, source, text);
      context = "";
      source = "";
      text = "";
      current = Field.NONE;
      translated = false;
      return entry;
    }
  }

  public static List<PoEntry> parse(final Reader reader) {
    final List<PoEntry> entries = new ArrayList<>();
    final Draft draft = new Draft();
    new BufferedReader(reader).lines().forEach(line -> readLine(line.strip(), draft, entries));
    if (draft.isComplete()) {
      entries.add(draft.finish());
    }
    return entries.stream().filter(entry -> !entry.msgid().isEmpty()).toList();
  }

  private static void readLine(final String line, final Draft draft, final List<PoEntry> entries) {
    if (line.isEmpty()) {
      finishIfComplete(draft, entries);
    } else if (line.startsWith(QUOTE)) {
      draft.append(unquote(line));
    } else if (!line.startsWith(COMMENT)) {
      readField(line, draft, entries);
    }
  }

  private static void readField(final String line, final Draft draft, final List<PoEntry> entries) {
    final int space = line.indexOf(' ');
    final String keyword = space < 0 ? line : line.substring(0, space);
    final Field field = fieldOf(keyword);
    // A new entry begins at its first field; the previous one is finished by then.
    if (field == Field.CONTEXT || field == Field.MSGID) {
      finishIfComplete(draft, entries);
    }
    if (field != Field.NONE && space > 0) {
      draft.start(field, unquote(line.substring(space)));
    }
  }

  private static void finishIfComplete(final Draft draft, final List<PoEntry> entries) {
    if (draft.isComplete()) {
      entries.add(draft.finish());
    }
  }

  private static Field fieldOf(final String keyword) {
    return switch (keyword) {
      case KEY_CONTEXT -> Field.CONTEXT;
      case KEY_ID -> Field.MSGID;
      case KEY_STRING -> Field.TEXT;
      default -> Field.NONE;
    };
  }

  // "..." (after the keyword) → the unescaped text between the quotes.
  private static String unquote(final String quoted) {
    final String value = quoted.strip();
    final String inner = value.substring(1, value.lastIndexOf('"'));
    final StringBuilder out = new StringBuilder(inner.length());
    boolean escaped = false;
    for (final char character : inner.toCharArray()) {
      if (escaped) {
        out.append(unescaped(character));
        escaped = false;
      } else if (character == BACKSLASH) {
        escaped = true;
      } else {
        out.append(character);
      }
    }
    return out.toString();
  }

  private static char unescaped(final char character) {
    return switch (character) {
      case 'n' -> '\n';
      case 't' -> '\t';
      default -> character;
    };
  }
}
