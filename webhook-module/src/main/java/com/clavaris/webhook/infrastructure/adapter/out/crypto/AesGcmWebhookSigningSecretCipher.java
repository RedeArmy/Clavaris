package com.clavaris.webhook.infrastructure.adapter.out.crypto;

import com.clavaris.common.infrastructure.adapter.out.crypto.AesGcmCipher;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookSigningSecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implements {@link WebhookSigningSecretCipher} by delegating to the shared {@link AesGcmCipher}
 * (common module) — AES-256-GCM, keyed from an environment variable ({@code
 * WEBHOOK_SECRET_ENCRYPTION_KEY}, base64-encoded 256-bit key), seeded once at startup, never
 * accepted via any HTTP endpoint — same "one trust root that doesn't derive from anything else"
 * posture the {@code PlatformClient} bootstrap credential already establishes (see this class's own
 * port Javadoc). Organization-module's own {@code AesGcmOrganizationSocialCredentialCipher}
 * delegates to the same shared primitive (SonarCloud-flagged duplication between the two before
 * this extraction; see {@link AesGcmCipher}'s own Javadoc for why only the byte manipulation is
 * shared, never the key material).
 */
@Component
class AesGcmWebhookSigningSecretCipher implements WebhookSigningSecretCipher {

  private final AesGcmCipher cipher;

  // Package-private: constructed only by Spring's own component scan (via @Component above) —
  // WebhookSigningSecretCipher (the port) is what every caller outside this package should depend
  // on, same convention as ResendMailSender's own identical constructor visibility.
  /* package */ AesGcmWebhookSigningSecretCipher(
      @Value("${clavaris.webhook.secret-encryption-key}") final String base64EncodedKey) {
    this.cipher = new AesGcmCipher(base64EncodedKey);
  }

  @Override
  public String encrypt(final String rawSecret) {
    return cipher.encrypt(rawSecret);
  }

  @Override
  public String decrypt(final String encryptedSecret) {
    return cipher.decrypt(encryptedSecret);
  }
}
