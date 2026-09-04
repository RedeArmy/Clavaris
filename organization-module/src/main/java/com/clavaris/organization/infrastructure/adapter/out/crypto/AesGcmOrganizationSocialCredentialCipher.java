package com.clavaris.organization.infrastructure.adapter.out.crypto;

import com.clavaris.common.infrastructure.adapter.out.crypto.AesGcmCipher;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implements {@link OrganizationSocialCredentialCipher} by delegating to the shared {@link
 * AesGcmCipher} (common module) — AES-256-GCM, {@code base64(iv||ciphertext)}, fresh 12-byte IV per
 * encryption — the established precedent for "a secret this system must present outbound in
 * cleartext, encrypted at rest," same mechanics webhook-module's own {@code
 * AesGcmWebhookSigningSecretCipher} also delegates to (SonarCloud-flagged duplication between the
 * two before this extraction; see {@link AesGcmCipher}'s own Javadoc for why only the byte
 * manipulation is shared, never the key material). Own dedicated key ({@code
 * SOCIAL_CREDENTIAL_ENCRYPTION_KEY}), deliberately not a reuse of {@code
 * WEBHOOK_SECRET_ENCRYPTION_KEY} — this codebase's established convention is that every at-rest
 * secret gets its own independently-rotatable key (confirmed by {@code .env.example}'s own
 * commentary on every sibling key in that file).
 *
 * <p>Known gap, same one {@link AesGcmCipher} already carries and deliberately doesn't solve here
 * either (see {@code technical-debt-register.md}): no re-encryption-on-rotation mechanism exists —
 * rotating {@code SOCIAL_CREDENTIAL_ENCRYPTION_KEY} without re-encrypting existing rows breaks
 * decryption of rows written under the old key.
 */
@SuppressWarnings("PMD.LongVariable")
@Component
class AesGcmOrganizationSocialCredentialCipher implements OrganizationSocialCredentialCipher {

  private final AesGcmCipher cipher;

  // Package-private: constructed only by Spring's own component scan — OrganizationSocialCredent
  // ialCipher (the port) is what every caller outside this package should depend on, same
  // convention as AesGcmWebhookSigningSecretCipher's own identical constructor visibility.
  /* package */ AesGcmOrganizationSocialCredentialCipher(
      @Value("${clavaris.organization.social-credential-encryption-key}")
          final String base64EncodedKey) {
    this.cipher = new AesGcmCipher(base64EncodedKey);
  }

  @Override
  public String encrypt(final String rawClientSecret) {
    return cipher.encrypt(rawClientSecret);
  }

  @Override
  public String decrypt(final String encryptedClientSecret) {
    return cipher.decrypt(encryptedClientSecret);
  }
}
