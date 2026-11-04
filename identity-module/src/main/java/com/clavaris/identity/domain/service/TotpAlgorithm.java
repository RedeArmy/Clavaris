package com.clavaris.identity.domain.service;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords over RFC 4226 HOTP: HMAC-SHA1, six digits, thirty-second
 * steps. These are the parameters every authenticator app implements by default, which is the whole
 * point: SHA-256/8 digits are in the RFC but several apps silently ignore them and then generate
 * codes the server rejects.
 *
 * <p>Pure domain logic with no framework: the JDK's own {@code Mac} is the only dependency.
 */
public final class TotpAlgorithm {

  /** Seconds a code stays the same. */
  public static final long STEP_SECONDS = 30;

  public static final int DIGITS = 6;

  private static final int SECRET_BYTES = 20;
  private static final String MAC_ALGORITHM = "HmacSHA1";
  private static final int DIGITS_MODULUS = 1_000_000;
  private static final int LOW_NIBBLE = 0x0F;
  private static final int SIGN_BIT_MASK = 0x7F;
  private static final int BYTE_MASK = 0xFF;
  private static final int BYTES_IN_INT = 4;
  private static final int SHIFT_24 = 24;
  private static final int SHIFT_16 = 16;
  private static final int SHIFT_8 = 8;

  private static final SecureRandom RANDOM = new SecureRandom();

  private TotpAlgorithm() {
    // Static utility.
  }

  /** A fresh 160-bit secret, the size RFC 4226 §4 recommends. */
  public static byte[] newSecret() {
    final byte[] secret = new byte[SECRET_BYTES];
    RANDOM.nextBytes(secret);
    return secret;
  }

  public static long stepAt(final long epochSeconds) {
    return Math.floorDiv(epochSeconds, STEP_SECONDS);
  }

  /** The six-digit code of a step, zero-padded. */
  public static String codeAt(final byte[] secret, final long step) {
    try {
      final Mac mac = Mac.getInstance(MAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret, MAC_ALGORITHM));
      final byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
      final int offset = hash[hash.length - 1] & LOW_NIBBLE;
      final int binary =
          ((hash[offset] & SIGN_BIT_MASK) << SHIFT_24)
              | ((hash[offset + 1] & BYTE_MASK) << SHIFT_16)
              | ((hash[offset + 2] & BYTE_MASK) << SHIFT_8)
              | (hash[offset + BYTES_IN_INT - 1] & BYTE_MASK);
      return String.format("%0" + DIGITS + "d", binary % DIGITS_MODULUS);
    } catch (final GeneralSecurityException e) {
      // HmacSHA1 is guaranteed on every JVM; a bad key length cannot happen for a non-empty secret.
      throw new IllegalStateException("HMAC-SHA1 is unavailable", e);
    }
  }

  /**
   * The step whose code equals {@code submitted}, looking {@code window} steps either side of now
   * to forgive a clock that is a little off, or empty when none does. Every candidate is compared,
   * and in constant time, so the answer does not leak which step was closest.
   */
  public static OptionalLong matchingStep(
      final byte[] secret, final String submitted, final long epochSeconds, final int window) {
    final byte[] given = submitted.getBytes(StandardCharsets.UTF_8);
    final long current = stepAt(epochSeconds);
    long match = -1;
    for (long step = current - window; step <= current + window; step++) {
      final byte[] expected = codeAt(secret, step).getBytes(StandardCharsets.UTF_8);
      if (MessageDigest.isEqual(expected, given)) {
        match = step;
      }
    }
    return match < 0 ? OptionalLong.empty() : OptionalLong.of(match);
  }

  /**
   * True when the text is exactly six ASCII digits, so anything else is refused before any work.
   */
  public static boolean looksLikeCode(final String submitted) {
    if (submitted == null || submitted.length() != DIGITS) {
      return false;
    }
    for (int i = 0; i < submitted.length(); i++) {
      final char digit = submitted.charAt(i);
      if (digit < '0' || digit > '9') {
        return false;
      }
    }
    return true;
  }

  /** The {@code otpauth://} URI an authenticator app reads from a QR code or accepts pasted. */
  public static String otpauthUri(
      final String issuer, final String accountLabel, final String base32Secret) {
    final String encodedIssuer = encode(issuer);
    return "otpauth://totp/"
        + encodedIssuer
        + ":"
        + encode(accountLabel)
        + "?secret="
        + base32Secret
        + "&issuer="
        + encodedIssuer
        + "&algorithm=SHA1&digits="
        + DIGITS
        + "&period="
        + STEP_SECONDS;
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
