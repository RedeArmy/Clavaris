package com.clavaris.clientregistry.domain.model;

/**
 * ADR-0009 §2: RFC 1035-shaped hostname validation for {@link ClientDomainConfig} — split into its
 * own class rather than left as private methods on that aggregate, both because it's a genuinely
 * self-contained, stateless check with no dependency on any of that aggregate's own fields, and
 * because keeping it there once this logic became hand-rolled character checks (see below) started
 * tripping PMD's own {@code GodClass} metric on an aggregate that already carries real domain
 * behavior of its own.
 *
 * <p>java:S5852: no regex anywhere in this class, deliberately. An "optional group wrapping a
 * bounded quantifier" pattern — this logic's own prior two attempts: first a single {@code
 * "+"}-repeated pattern over the whole hostname, then a single-label pattern reused in a loop — is
 * exactly the shape that rule's static analysis flags regardless of the quantifier's own bound or
 * how the pattern is invoked. A hostname label's own grammar (bounded length, first/last char
 * alphanumeric, alphanumeric/hyphen between) is simple enough that a real regex was never buying
 * more than syntax density here.
 */
// LongVariable: MAX_HOSTNAME_LENGTH/SUPPRESS_ONLY_ONE_RETURN name exactly what they hold, same
// idiom this module's every other value object already uses. ShortVariable: the single-character
// loop/parameter variables below (c, i) are exactly what they are — a character and an index —
// same convention ClientDomainConfig's own class-level suppression already establishes.
@SuppressWarnings({"PMD.LongVariable", "PMD.ShortVariable"})
final class HostnameValidator {

  private static final int MAX_HOSTNAME_LENGTH = 253;
  private static final int MAX_LABEL_LENGTH = 63;
  private static final int MIN_LABEL_COUNT = 2;
  private static final String SUPPRESS_ONLY_ONE_RETURN = "PMD.OnlyOneReturn";

  private HostnameValidator() {
    // Utility class — every method is static, no instance state to construct.
  }

  // A scheme, path, port, or trailing dot is deliberately rejected by MIN_LABEL_COUNT/isValidLabel
  // together — this value is compared verbatim against the inbound Host header
  // (CustomDomainRequestRewriteFilter), not parsed as a URI.
  // PMD.OnlyOneReturn: "too long overall" / "too few labels" / "a label doesn't validate" /
  // "valid" are four independent, equally valid exits.
  @SuppressWarnings(SUPPRESS_ONLY_ONE_RETURN)
  /* package */ static boolean isValid(final String hostname) {
    if (hostname.length() > MAX_HOSTNAME_LENGTH) {
      return false;
    }
    final String[] labels = hostname.split("\\.", -1);
    if (labels.length < MIN_LABEL_COUNT) {
      return false;
    }
    for (final String label : labels) {
      if (!isValidLabel(label)) {
        return false;
      }
    }
    return true;
  }

  // PMD.OnlyOneReturn: "wrong length" / "first or last char not alphanumeric" / "valid" are three
  // independent, equally valid exits.
  @SuppressWarnings(SUPPRESS_ONLY_ONE_RETURN)
  private static boolean isValidLabel(final String label) {
    final int length = label.length();
    if (length < 1 || length > MAX_LABEL_LENGTH) {
      return false;
    }
    if (!isAsciiAlphanumeric(label.charAt(0)) || !isAsciiAlphanumeric(label.charAt(length - 1))) {
      return false;
    }
    for (int i = 1; i < length - 1; i++) {
      final char c = label.charAt(i);
      if (!isAsciiAlphanumeric(c) && c != '-') {
        return false;
      }
    }
    return true;
  }

  private static boolean isAsciiAlphanumeric(final char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
  }
}
