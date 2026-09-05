package com.clavaris.identity.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * ADR-0024 §4: an account's optional, additional sign-up/sign-in identifier — {@link Email}'s
 * sibling value object, same normalization rationale: lower-cased on construction so that {@code
 * (organizationId, username)} uniqueness (a partial unique index — {@code accounts.username} is
 * nullable, so most rows never participate in it at all) can never be defeated by case variation of
 * the same username.
 */
public record Username(String value) {

  // Letters, digits, underscore, hyphen — no spaces, no "@" (so a submitted identifier's shape
  // alone already disambiguates it from an email address at the login form, same detection
  // LoginController's own single-identifier-field routing relies on).
  private static final Pattern SHAPE = Pattern.compile("^[a-z0-9_-]+$");

  private static final int MIN_LENGTH = 3;
  private static final int MAX_LENGTH = 32;

  public Username {
    Objects.requireNonNull(value, "Username value must not be null");
    value = value.strip().toLowerCase(Locale.ROOT);
    if (value.length() < MIN_LENGTH
        || value.length() > MAX_LENGTH
        || !SHAPE.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "Not a valid username (3-32 characters, letters/digits/underscore/hyphen only): "
              + value);
    }
  }
}
