package com.clavaris.webhook.infrastructure.adapter.out.crypto;

import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookSigningSecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implements {@link WebhookSigningSecretCipher} — AES-256-GCM, keyed from an environment variable
 * ({@code WEBHOOK_SECRET_ENCRYPTION_KEY}, base64-encoded 256-bit key), seeded once at startup,
 * never accepted via any HTTP endpoint — same "one trust root that doesn't derive from anything
 * else" posture the {@code PlatformClient} bootstrap credential already establishes (see this
 * class's own port Javadoc). GCM is an authenticated mode: a tampered ciphertext (a corrupted row,
 * or an attempt to substitute another endpoint's encrypted secret into this one's column) fails
 * decryption loudly rather than silently returning garbage key material.
 *
 * <p>Stored shape: {@code base64(iv || ciphertext-with-embedded-auth-tag)} — one column, one value,
 * no separate iv column to keep in sync. A fresh random IV is generated per {@link #encrypt}, never
 * reused (GCM's own hard requirement: reusing an IV with the same key breaks its confidentiality
 * guarantee entirely) — 96 bits, the size GCM itself is optimized for.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.ShortVariable"})
@Component
class AesGcmWebhookSigningSecretCipher implements WebhookSigningSecretCipher {

  private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int GCM_IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  // Package-private: constructed only by Spring's own component scan (via @Component above) —
  // WebhookSigningSecretCipher (the port) is what every caller outside this package should depend
  // on, same convention as ResendMailSender's own identical constructor visibility.
  /* package */ AesGcmWebhookSigningSecretCipher(
      @Value("${clavaris.webhook.secret-encryption-key}") final String base64EncodedKey) {
    this.key = new SecretKeySpec(Base64.getDecoder().decode(base64EncodedKey), KEY_ALGORITHM);
  }

  @Override
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
      // loudly, this can't legitimately happen" stance as WebhookSignature's own identical catch.
      throw new IllegalStateException("Failed to encrypt webhook signing secret", e);
    }
  }

  @Override
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
      // A real failure mode, unlike encrypt's own (corrupted/tampered ciphertext, or a key
      // mismatch after WEBHOOK_SECRET_ENCRYPTION_KEY was rotated without re-encrypting existing
      // rows — the latter is a real operational gap, tracked as future work, not solved here) —
      // still fails loudly rather than returning garbage that would silently produce an invalid
      // HMAC signature no consumer could ever verify.
      throw new IllegalStateException("Failed to decrypt webhook signing secret", e);
    }
  }
}
