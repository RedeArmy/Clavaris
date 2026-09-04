package com.clavaris.common.infrastructure.adapter.out.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared AES-256-GCM reversible-encryption primitive for every module's own "must present this
 * secret outbound in cleartext, encrypted at rest" cipher — webhook-module's own {@code
 * AesGcmWebhookSigningSecretCipher} and organization-module's own {@code
 * AesGcmOrganizationSocialCredentialCipher} both delegate here instead of duplicating the same
 * IV/ciphertext byte manipulation (SonarCloud-flagged duplication) — same root cause {@code
 * AbstractEventOutboxEntity}'s own Javadoc already documents for the JPA-entity equivalent of this
 * problem.
 *
 * <p>Deliberately a plain, non-Spring-managed class, not a shared {@code @Component}: each module
 * still owns its own port interface, its own {@code @Value}-injected key property, and its own
 * dedicated encryption key (this codebase's established convention — every at-rest secret gets its
 * own independently-rotatable key). Only the actual AES-GCM mechanics are shared, never the key
 * material or the Spring wiring around it — each adapter constructs its own instance in its own
 * constructor and delegates {@code encrypt}/{@code decrypt} to it.
 *
 * <p>Stored shape: {@code base64(iv || ciphertext-with-embedded-auth-tag)} — one string, no
 * separate iv column to keep in sync. A fresh random IV is generated per {@link #encrypt}, never
 * reused (GCM's own hard requirement: reusing an IV with the same key breaks its confidentiality
 * guarantee entirely) — 96 bits, the size GCM itself is optimized for.
 *
 * <p>Known gap, unchanged by this extraction: no re-encryption-on-key-rotation mechanism exists —
 * rotating a caller's own key without re-encrypting existing rows breaks decryption of rows written
 * under the old key (tracked per-module, in each caller's own technical-debt-register.md row).
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.LongVariable"})
public final class AesGcmCipher {

  private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int GCM_IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  public AesGcmCipher(final String base64EncodedKey) {
    this.key = new SecretKeySpec(Base64.getDecoder().decode(base64EncodedKey), KEY_ALGORITHM);
  }

  public String encrypt(final String rawSecret) {
    final byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
    secureRandom.nextBytes(iv);
    try {
      final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      final byte[] ciphertext = cipher.doFinal(rawSecret.getBytes(StandardCharsets.UTF_8));
      final byte[] ivAndCiphertext = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.length);
      System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(ivAndCiphertext);
    } catch (final GeneralSecurityException e) {
      // AES/GCM is a JDK-guaranteed algorithm on every conformant JVM — this is a programming
      // error (a malformed key), not a runtime condition callers should have to handle, same "fail
      // loudly, this can't legitimately happen" stance every prior copy of this code established.
      throw new IllegalStateException("AES-GCM encryption failed", e);
    }
  }

  public String decrypt(final String encryptedSecret) {
    final byte[] ivAndCiphertext = Base64.getDecoder().decode(encryptedSecret);
    final byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
    final byte[] ciphertext = new byte[ivAndCiphertext.length - GCM_IV_LENGTH_BYTES];
    System.arraycopy(ivAndCiphertext, 0, iv, 0, iv.length);
    System.arraycopy(ivAndCiphertext, iv.length, ciphertext, 0, ciphertext.length);
    try {
      final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (final GeneralSecurityException e) {
      // A real failure mode, unlike encrypt's own: corrupted/tampered ciphertext, or a key
      // mismatch after the caller's own key was rotated without re-encrypting existing rows —
      // still fails loudly rather than returning garbage the caller could mistake for real
      // cleartext.
      throw new IllegalStateException("AES-GCM decryption failed", e);
    }
  }
}
