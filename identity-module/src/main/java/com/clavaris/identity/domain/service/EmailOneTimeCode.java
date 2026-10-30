package com.clavaris.identity.domain.service;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * ADR-0034 (supersedes the six-digit format of ADR-0024): a short, human-typeable one-time code —
 * the {@code CODE} counterpart to {@link RefreshTokenSecret}'s own 256-bit link-shaped token. Used
 * wherever {@code VerificationToken}/{@code AbstractVerificationToken}'s own hash/consume/{@code
 * isActive} lifecycle is reused unchanged but the raw value must fit in an email a person retypes
 * by hand, not a clickable URL: email verification codes, passwordless email sign-in codes,
 * device-trust challenge codes. Hashed via {@link RefreshTokenSecret#hash} before persistence,
 * never stored in cleartext, same BR-ID-04/BR-ID-05 invariant either raw-value shape must satisfy.
 *
 * <p>Six characters from a 32-symbol alphabet: the digits 2 to 9 and the capital letters A to Z
 * without {@code I} and {@code O}. That is exactly 5 bits per character, 30 bits in all
 * (1,073,741,824 codes, about a thousand times the 1,000,000 of six decimal digits), and drawing
 * each character with {@code nextInt(32)} is uniform with no modulo bias, which an alphabet of any
 * other size would not give. {@code 0} and {@code 1} are left out along with {@code O} and {@code
 * I}, the letters they are mistaken for, so no pair a person can confuse exists in a code. Capitals
 * only, so there is no case to get wrong, and {@link #normalize} accepts what people actually type
 * (lower case, spaces, hyphens). {@code B/8}, {@code S/5}, {@code Z/2} and {@code G/6} are kept:
 * dropping them as well would leave under 30 bits, and a mail shows the code large.
 *
 * <p>Thirty bits is safe <b>only when paired with attempt throttling</b> (SP 800-63B-4 §3.1.3.2
 * requires it under 64 bits). That lives at the HTTP layer and in {@code CodeLockout} (ADR-0033):
 * this class alone is not a defense.
 */
public final class EmailOneTimeCode {

  private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
  private static final int CODE_LENGTH = 6;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private EmailOneTimeCode() {
    // Static utility — not instantiable, same convention as RefreshTokenSecret.
  }

  /** A fresh, cryptographically random code, never persisted: only its hash is. */
  public static String generate() {
    final StringBuilder code = new StringBuilder(CODE_LENGTH);
    for (int index = 0; index < CODE_LENGTH; index++) {
      code.append(ALPHABET.charAt(SECURE_RANDOM.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }

  /**
   * What was typed, as the form a code is hashed in: capitals, with the spaces and hyphens people
   * add when copying a code removed. A digit string (a code issued before ADR-0034) is unchanged,
   * so one still in flight keeps working. Not a validity check: a value that is not a code simply
   * matches nothing.
   */
  public static String normalize(final String typed) {
    if (typed == null) {
      return "";
    }
    final StringBuilder kept = new StringBuilder(typed.length());
    for (int index = 0; index < typed.length(); index++) {
      final char character = typed.charAt(index);
      if (!isSeparator(character)) {
        kept.append(character);
      }
    }
    return kept.toString().toUpperCase(Locale.ROOT);
  }

  // What people add when they copy or read a code out: spaces, hyphens, and the dashes a word
  // processor turns a hyphen into.
  private static boolean isSeparator(final char character) {
    return Character.isWhitespace(character)
        || character == '-'
        || character == '\u2013'
        || character == '\u2014';
  }
}
