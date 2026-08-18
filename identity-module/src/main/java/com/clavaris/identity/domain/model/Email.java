package com.clavaris.identity.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An account's email address. Normalized (trimmed, lower-cased) on construction so that {@code
 * (organizationId, email)} uniqueness (data-model.md §3) can never be defeated by case or
 * whitespace variation of the same address — normalization happens here, once, rather than being
 * re-derived (or forgotten) at every call site that compares or persists an email.
 */
public record Email(String value) {

  // Deliberately permissive (not RFC 5322-exhaustive): this rejects the obviously malformed
  // (no "@", no domain) without rejecting a real address the full RFC grammar would accept but
  // this pattern is stricter about — false rejections of a real user's address are worse than
  // letting a slightly unusual one through, since delivery of the verification email (BR-ID-05)
  // is the actual proof an address is valid, not this pattern.
  //
  // The domain part is split into dot-separated labels ([^\s@.]+, dots excluded) rather than
  // matched with two adjacent [^\s@]+ groups either side of a literal dot: the original version
  // let both groups match "." too, which let the engine try every possible split point between
  // them on a domain-less input before failing — O(n²) backtracking, flagged live by static
  // analysis as superlinear. Excluding "." from each label's own character class removes the
  // ambiguity entirely: every character has exactly one way to be consumed, so there's nothing
  // left to backtrack over.
  private static final Pattern FORMAT = Pattern.compile("^[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+$");

  // RFC 5321 §4.5.3.1.3: 254 characters is the maximum total length of a valid email address —
  // not an arbitrary number, the actual protocol limit. Enforced BEFORE the regex runs, not just
  // as an additional sanity check: java.util.regex implements a quantified group like
  // (?:\.[^\s@.]+)+ above via recursive descent, one stack frame per repetition, so an
  // unbounded input with enough dots risks a genuine StackOverflowError, flagged live by static
  // analysis — distinct from the backtracking-cost concern the group's own shape already
  // resolves. Bounding the input first means the maximum possible repetition count, and so the
  // maximum recursion depth, is a small fixed number regardless of how the pattern is written.
  private static final int MAX_LENGTH = 254;

  public Email {
    Objects.requireNonNull(value, "Email value must not be null");
    value = value.strip().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("Not a valid email address: " + value);
    }
  }
}
