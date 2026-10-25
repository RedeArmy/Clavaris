package com.clavaris.clientregistry.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * How a client secret the server itself generated is stored and checked: {@code {sha256}} followed
 * by the unpadded URL-safe Base64 of the SHA-256 of the secret.
 *
 * <p><b>Why not Argon2id here.</b> Argon2id (ADR-0005) exists to make guessing a
 * <em>low-entropy</em> secret, a human's password, expensive. A client secret is not that: the
 * generators in this module draw 256 bits from {@code SecureRandom}, so there is nothing to guess.
 * An attacker holding a stolen hash faces 2^256 candidates whether the hash costs a microsecond or
 * a second, and SHA-256 is preimage resistant. What Argon2 would add is a ~60 ms CPU and 16 MiB
 * cost on <em>every</em> {@code /oauth2/token} call, the endpoint every consuming application hits,
 * which at ten concurrent requests on a small host already pushed p95 past the 300 ms target and
 * made the bulkhead reject legitimate clients. API keys of this kind are stored this way on purpose
 * elsewhere (a fast hash of a long random secret, never of a human-chosen one).
 *
 * <p><b>The one rule that makes this safe</b> is that the input is long and random. {@link #hash}
 * therefore refuses a secret shorter than {@link #MIN_LENGTH} characters: it cannot prove entropy,
 * but it stops the obvious misuse, someone routing an operator- or user-chosen secret through the
 * cheap path. The operator-chosen bootstrap secret of the platform client keeps going through
 * Argon2 for exactly that reason.
 *
 * <p>No salt: the input is unique and unguessable, so a precomputed table is useless, and the
 * digest must be reproducible from the presented secret alone. The comparison is constant-time.
 *
 * <p>Legacy {@code $argon2id$...} hashes written before this scheme existed are not handled here;
 * they keep being verified by the Argon2 encoder until the secret is next rotated.
 */
public final class HighEntropyClientSecret {

  /** Marks a stored hash as this scheme. Cannot collide with {@code $argon2...}. */
  public static final String PREFIX = "{sha256}";

  /**
   * A secret of 256 random bits is 43 URL-safe Base64 characters; 32 leaves room for a different
   * generator while still refusing anything a person would type.
   */
  public static final int MIN_LENGTH = 32;

  private HighEntropyClientSecret() {
    // Static utility.
  }

  public static String hash(final String rawSecret) {
    if (rawSecret == null || rawSecret.length() < MIN_LENGTH) {
      throw new IllegalArgumentException(
          "only a generated, high-entropy secret (at least "
              + MIN_LENGTH
              + " characters) may use the fast hash; hash any other secret with Argon2");
    }
    return PREFIX + digestOf(rawSecret);
  }

  /** True when {@code stored} was written by this scheme. */
  public static boolean isHash(final String stored) {
    return stored != null && stored.startsWith(PREFIX);
  }

  /** Constant-time check of a presented secret against a stored hash of this scheme. */
  public static boolean matches(final String presentedSecret, final String stored) {
    return presentedSecret != null
        && isHash(stored)
        && MessageDigest.isEqual(
            digestOf(presentedSecret).getBytes(StandardCharsets.US_ASCII),
            stored.substring(PREFIX.length()).getBytes(StandardCharsets.US_ASCII));
  }

  private static String digestOf(final String secret) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java platform", e);
    }
  }
}
