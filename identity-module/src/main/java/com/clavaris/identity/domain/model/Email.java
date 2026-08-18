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
  // Only checks the local-part/@/domain shape — no quantified group, no repetition of any kind.
  // Two earlier versions of this pattern tried to also validate the domain's dot-separated
  // labels here: the first let both sides of the literal dot match "." too, letting the engine
  // try every possible split point on a domain-less input before failing (O(n²) backtracking,
  // flagged by static analysis as superlinear); the fix for that — a repeated group,
  // (?:\.[^\s@.]+)+ — was itself then flagged for a genuine stack-overflow risk, since
  // java.util.regex implements quantified *group* repetition via recursive descent, one stack
  // frame per repetition, unlike a plain character-class quantifier. A length guard on the input
  // (still enforced below, RFC 5321 §4.5.3.1.3) mitigates the practical risk but doesn't change
  // what the pattern itself statically contains, so the warning persisted regardless. Moving the
  // "at least one interior dot, no empty label" check into plain String operations
  // (hasValidDomainShape below) removes the structural cause instead of working around it —
  // there is no regex construct left that can backtrack or recurse, at any input length.
  private static final Pattern LOCAL_AND_DOMAIN = Pattern.compile("^[^\\s@]+@[^\\s@]+$");

  // RFC 5321 §4.5.3.1.3: 254 characters is the maximum total length of a valid email address —
  // not an arbitrary number, the actual protocol limit. Kept as a first, cheap rejection even
  // though the pattern above no longer has any repetition for it to bound the cost of.
  private static final int MAX_LENGTH = 254;

  public Email {
    Objects.requireNonNull(value, "Email value must not be null");
    value = value.strip().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || value.length() > MAX_LENGTH || !hasValidShape(value)) {
      throw new IllegalArgumentException("Not a valid email address: " + value);
    }
  }

  private static boolean hasValidShape(final String candidate) {
    // Single boolean expression, not an early-return guard clause: hasValidDomainShape must never
    // run against a candidate that doesn't even contain "@" (indexOf would return -1, and
    // substring(0) would silently "validate" the whole string as a domain) — short-circuiting
    // && is what keeps that ordering a guarantee rather than a convention to remember.
    return LOCAL_AND_DOMAIN.matcher(candidate).matches()
        && hasValidDomainShape(candidate.substring(candidate.indexOf('@') + 1));
  }

  // Equivalent to the regex-repetition approach this replaced (a domain must resolve to two or
  // more non-empty, dot-separated labels — e.g. "example.com", not just "example") but expressed
  // as plain String scans: each of these is a single linear pass with no backtracking or
  // recursion possible, at any input length.
  private static boolean hasValidDomainShape(final String domain) {
    return domain.contains(".")
        && !domain.startsWith(".")
        && !domain.endsWith(".")
        && !domain.contains("..");
  }
}
