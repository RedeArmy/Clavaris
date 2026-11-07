package com.clavaris.common.infrastructure.adapter.out.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AES-256-GCM primitive under every at-rest secret store: {@link EnvelopeCipher} builds the
 * versioned, key-identified, context-bound format on top of it, and each module's own cipher
 * adapter owns its port, its key property and its key (this codebase's convention: every at-rest
 * secret gets its own independently rotatable key). Only the byte manipulation is shared, never key
 * material or Spring wiring.
 *
 * <p>Plain shape: {@code base64(iv || ciphertext-with-embedded-auth-tag)}, one string, no separate
 * iv column. A fresh random 96-bit IV per {@link #encrypt}, never reused (GCM's own hard
 * requirement). The overloads taking {@code associatedData} authenticate that data as well, without
 * storing it: a ciphertext then decrypts only where the same data is supplied, which is what stops
 * one row's secret being copied into another row (TD-SEC-086). The overloads without it are the
 * format every row written before that change uses.
 *
 * <p>The key must be exactly 256 bits: AES accepts 128 and 192 as well, so a shorter key would
 * otherwise be used silently, below the strength the documentation promises.
 */
public final class AesGcmCipher {

  private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int KEY_LENGTH_BYTES = 32;
  private static final int GCM_IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * @throws IllegalArgumentException if the key is not valid base64 or not 256 bits; the message
   *     never contains key material
   */
  public AesGcmCipher(final String base64EncodedKey) {
    final byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(base64EncodedKey);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("The encryption key is not valid base64", e);
    }
    if (decoded.length != KEY_LENGTH_BYTES) {
      throw new IllegalArgumentException(
          "The encryption key must be 256 bits (32 bytes, openssl rand -base64 32); it has "
              + decoded.length * 8
              + " bits");
    }
    this.key = new SecretKeySpec(decoded, KEY_ALGORITHM);
  }

  /** The legacy shape: no associated data. */
  public String encrypt(final String rawSecret) {
    return encrypt(rawSecret, null);
  }

  /** The legacy shape: no associated data. */
  public String decrypt(final String encryptedSecret) {
    return decrypt(encryptedSecret, null);
  }

  /**
   * @param associatedData authenticated with the secret but not stored; {@code null} for none
   */
  public String encrypt(final String rawSecret, final byte[] associatedData) {
    final byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
    secureRandom.nextBytes(iv);
    try {
      final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      if (associatedData != null) {
        cipher.updateAAD(associatedData);
      }
      final byte[] ciphertext = cipher.doFinal(rawSecret.getBytes(StandardCharsets.UTF_8));
      final byte[] ivAndCiphertext = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.length);
      System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(ivAndCiphertext);
    } catch (final GeneralSecurityException e) {
      // AES/GCM is guaranteed on every conformant JVM and the key length is checked in the
      // constructor, so this is a programming error, not a condition callers should handle.
      throw new IllegalStateException("AES-GCM encryption failed", e);
    }
  }

  /**
   * @param associatedData must equal what {@link #encrypt(String, byte[])} was given; {@code null}
   *     for none
   * @throws IllegalStateException if the data was tampered with, the associated data differs, or
   *     the key is not the one it was encrypted under; never returns garbage
   */
  public String decrypt(final String encryptedSecret, final byte[] associatedData) {
    final byte[] ivAndCiphertext;
    try {
      ivAndCiphertext = Base64.getDecoder().decode(encryptedSecret);
    } catch (final IllegalArgumentException e) {
      // Text that is not base64 is as unreadable as a tampered value, and callers handle one type.
      throw new IllegalStateException("AES-GCM decryption failed", e);
    }
    if (ivAndCiphertext.length <= GCM_IV_LENGTH_BYTES) {
      throw new IllegalStateException("AES-GCM decryption failed");
    }
    final byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
    final byte[] ciphertext = new byte[ivAndCiphertext.length - GCM_IV_LENGTH_BYTES];
    System.arraycopy(ivAndCiphertext, 0, iv, 0, iv.length);
    System.arraycopy(ivAndCiphertext, iv.length, ciphertext, 0, ciphertext.length);
    try {
      final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      if (associatedData != null) {
        cipher.updateAAD(associatedData);
      }
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM decryption failed", e);
    }
  }
}
