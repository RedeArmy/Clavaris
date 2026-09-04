package com.clavaris.organization.infrastructure.adapter.out.crypto;

import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialCipher;
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
 * Implements {@link OrganizationSocialCredentialCipher} — deliberately an exact structural clone of
 * webhook-module's own {@code AesGcmWebhookSigningSecretCipher} (AES-256-GCM, {@code
 * base64(iv||ciphertext)}, fresh 12-byte {@link SecureRandom} IV per {@link #encrypt}), the
 * established precedent for "a secret this system must present outbound in cleartext, encrypted at
 * rest." Own dedicated key ({@code SOCIAL_CREDENTIAL_ENCRYPTION_KEY}), deliberately not a reuse of
 * {@code WEBHOOK_SECRET_ENCRYPTION_KEY} — this codebase's established convention is that every
 * at-rest secret gets its own independently-rotatable key (confirmed by {@code .env.example}'s own
 * commentary on every sibling key in that file).
 *
 * <p>Known gap, same one {@code AesGcmWebhookSigningSecretCipher} already carries and deliberately
 * doesn't solve here either (see {@code technical-debt-register.md}): no re-encryption-on-rotation
 * mechanism exists — rotating {@code SOCIAL_CREDENTIAL_ENCRYPTION_KEY} without re-encrypting
 * existing rows breaks decryption of rows written under the old key.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.ShortVariable"})
@Component
class AesGcmOrganizationSocialCredentialCipher implements OrganizationSocialCredentialCipher {

  private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int GCM_IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  // Package-private: constructed only by Spring's own component scan — OrganizationSocialCredent
  // ialCipher (the port) is what every caller outside this package should depend on, same
  // convention as AesGcmWebhookSigningSecretCipher's own identical constructor visibility.
  /* package */ AesGcmOrganizationSocialCredentialCipher(
      @Value("${clavaris.organization.social-credential-encryption-key}")
          final String base64EncodedKey) {
    this.key = new SecretKeySpec(Base64.getDecoder().decode(base64EncodedKey), KEY_ALGORITHM);
  }

  @Override
  public String encrypt(final String rawClientSecret) {
    final byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
    secureRandom.nextBytes(iv);
    try {
      final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      final byte[] ciphertext = cipher.doFinal(rawClientSecret.getBytes(StandardCharsets.UTF_8));
      final byte[] ivAndCiphertext = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.length);
      System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(ivAndCiphertext);
    } catch (final GeneralSecurityException e) {
      // AES/GCM is a JDK-guaranteed algorithm on every conformant JVM — this is a programming
      // error (a malformed key), not a runtime condition callers should have to handle, same
      // "fail loudly, this can't legitimately happen" stance AesGcmWebhookSigningSecretCipher's
      // own identical catch already establishes.
      throw new IllegalStateException("Failed to encrypt social OAuth client secret", e);
    }
  }

  @Override
  public String decrypt(final String encryptedClientSecret) {
    final byte[] ivAndCiphertext = Base64.getDecoder().decode(encryptedClientSecret);
    final byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
    final byte[] ciphertext = new byte[ivAndCiphertext.length - GCM_IV_LENGTH_BYTES];
    System.arraycopy(ivAndCiphertext, 0, iv, 0, iv.length);
    System.arraycopy(ivAndCiphertext, iv.length, ciphertext, 0, ciphertext.length);
    try {
      final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (final GeneralSecurityException e) {
      // A real failure mode, unlike encrypt's own — corrupted/tampered ciphertext, or a key
      // mismatch after SOCIAL_CREDENTIAL_ENCRYPTION_KEY was rotated without re-encrypting
      // existing rows (this class's own Javadoc names this as a real, acknowledged gap).
      throw new IllegalStateException("Failed to decrypt social OAuth client secret", e);
    }
  }
}
