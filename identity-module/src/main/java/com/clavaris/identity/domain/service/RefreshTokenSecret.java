package com.clavaris.identity.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * BR-ID-03: pure-JDK, framework-free — same "no Spring/JPA leaking into the domain layer" rule
 * {@link PasswordPolicy} already follows. Unlike {@code Argon2PasswordHasher} (a deliberately slow
 * KDF for low-entropy user-chosen passwords), refresh token values are already high-entropy random
 * strings this system itself generates — SHA-256, a fast general-purpose hash, is the correct,
 * standard choice here, not a weaker substitute for Argon2id; hashing a password with SHA-256 would
 * be wrong (too fast against brute force), but hashing an already-256-bit-random token with it is
 * exactly right (nothing to brute-force, the value's entropy is the defense, not the hash's cost).
 */
public final class RefreshTokenSecret {

  // 256 bits of randomness — matches the entropy a modern access/refresh token is expected to
  // carry; base64url keeps the value URL-safe without padding noise in a redirect/JSON context.
  // Descriptive over PMD's default LongVariable threshold, kept in full rather than abbreviated.
  @SuppressWarnings("PMD.LongVariable")
  private static final int RAW_VALUE_BYTE_LENGTH = 32;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private RefreshTokenSecret() {
    // Static utility — not instantiable.
  }

  /** A fresh, cryptographically random raw token value — never persisted, only its hash is. */
  public static String generateRawValue() {
    final byte[] randomBytes = new byte[RAW_VALUE_BYTE_LENGTH];
    SECURE_RANDOM.nextBytes(randomBytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
  }

  /**
   * Hex-encoded SHA-256 of {@code rawValue} — this, never the raw value, is what gets persisted.
   */
  public static String hash(final String rawValue) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] hashed = digest.digest(rawValue.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hashed);
    } catch (final NoSuchAlgorithmException e) {
      // SHA-256 is a JDK-mandatory algorithm — same reasoning as RsaKeyPairs' own equivalent catch.
      throw new IllegalStateException("SHA-256 MessageDigest not available on this JVM", e);
    }
  }
}
