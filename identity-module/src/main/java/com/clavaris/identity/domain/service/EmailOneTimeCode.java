package com.clavaris.identity.domain.service;

import java.security.SecureRandom;

/**
 * ADR-0024: a short, human-typeable one-time code — the {@code CODE} counterpart to {@link
 * RefreshTokenSecret}'s own 256-bit link-shaped token. Used wherever {@code
 * VerificationToken}/{@code AbstractVerificationToken}'s own hash/consume/{@code isActive}
 * lifecycle is reused unchanged but the raw value must fit in an SMS/email a person retypes by
 * hand, not a clickable URL: email verification codes, passwordless email sign-in codes,
 * device-trust challenge codes.
 *
 * <p>Six decimal digits (1,000,000 possibilities), zero-padded (e.g. {@code "042817"}) — the same
 * length virtually every real-world OTP implementation (TOTP/RFC 6238, Clerk's own fixed test code,
 * every SMS 2FA code in production use) converges on: short enough to type from memory, long enough
 * that guessing it is impractical <b>only when paired with attempt throttling</b> — this class
 * alone is not a complete defense (see {@code technical-debt-register.md}'s own brute-force-cap row
 * for where that throttling actually lives, at the HTTP layer, since the confirming service has no
 * stable per-account identifier to key an in-process counter by before a successful lookup). Hashed
 * via {@link RefreshTokenSecret#hash} exactly like a link token before persistence — never stored
 * in cleartext, same BR-ID-04/BR-ID-05 invariant either raw-value shape must satisfy.
 */
@SuppressWarnings("PMD.LongVariable")
public final class EmailOneTimeCode {

  private static final int CODE_DIGIT_COUNT = 6;
  private static final int CODE_UPPER_BOUND_EXCLUSIVE = 1_000_000;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private EmailOneTimeCode() {
    // Static utility — not instantiable, same convention as RefreshTokenSecret.
  }

  /**
   * A fresh, cryptographically random 6-digit code, zero-padded — never persisted, only its hash
   * is.
   */
  public static String generate() {
    final int value = SECURE_RANDOM.nextInt(CODE_UPPER_BOUND_EXCLUSIVE);
    return String.format("%0" + CODE_DIGIT_COUNT + "d", value);
  }
}
