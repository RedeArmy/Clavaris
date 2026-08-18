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
  private static final Pattern FORMAT = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  public Email {
    Objects.requireNonNull(value, "Email value must not be null");
    value = value.strip().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("Not a valid email address: " + value);
    }
  }
}
