package com.clavaris.webhook.infrastructure.adapter.out.crypto;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.crypto.EnvelopeCipher;
import com.clavaris.common.infrastructure.adapter.out.crypto.SecretReencryptionStore;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Target;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookSigningSecretCipher;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implements {@link WebhookSigningSecretCipher} on the shared {@link EnvelopeCipher} (common
 * module): AES-256-GCM keyed from an environment variable ({@code WEBHOOK_SECRET_ENCRYPTION_KEY}, a
 * base64 256-bit key), seeded once at startup and never accepted through any HTTP endpoint, the
 * same "one trust root that doesn't derive from anything else" posture the {@code PlatformClient}
 * bootstrap credential establishes. The stored value names the key that encrypted it and is bound
 * to its endpoint (TD-SEC-086); the same mechanics organization-module's social-credential cipher
 * uses.
 *
 * <p>To rotate: set {@code clavaris.webhook.secret-encryption-key-id} to a new id with the new key,
 * and put the old one in {@code clavaris.webhook.previous-keys} as {@code id:base64}. A secret
 * written before key ids existed is read with the key called {@code 1}.
 */
@Component
class AesGcmWebhookSigningSecretCipher
    implements WebhookSigningSecretCipher, SecretReencryptionStore {

  private static final String PURPOSE = "webhook-signing-secret";

  // Both the current and the previous secret of an endpoint are bound to the endpoint's id.
  private static final String CONTEXT_PREFIX = "webhook_endpoints.signing_secret/";

  private final EnvelopeCipher cipher;

  // Package-private: constructed only by Spring's own component scan — WebhookSigningSecretCipher
  // (the port) is what every caller outside this package should depend on.
  /* package */ AesGcmWebhookSigningSecretCipher(
      @Value("${clavaris.webhook.secret-encryption-key}") final String base64EncodedKey,
      @Value("${clavaris.webhook.secret-encryption-key-id:1}") final String keyId,
      @Value("${clavaris.webhook.previous-keys:}") final String previousKeys,
      final ObjectProvider<SecurityMetricsRecorder> metrics) {
    this.cipher =
        new EnvelopeCipher(
            PURPOSE,
            keyId,
            base64EncodedKey,
            previousKeys,
            () -> {
              final SecurityMetricsRecorder recorder = metrics.getIfAvailable();
              if (recorder != null) {
                recorder.increment("clavaris.crypto.legacy_envelope_read", "store", PURPOSE);
              }
            });
  }

  @Override
  public String encrypt(final UUID endpointId, final String rawSecret) {
    return cipher.encrypt(rawSecret, context(endpointId));
  }

  @Override
  public String decrypt(final UUID endpointId, final String encryptedSecret) {
    return cipher.decrypt(encryptedSecret, context(endpointId));
  }

  @Override
  public String store() {
    return PURPOSE;
  }

  @Override
  public EnvelopeCipher envelope() {
    return cipher;
  }

  // Both the current and the previous secret of an endpoint, each bound to the endpoint's id.
  @Override
  public List<Target> targets() {
    return List.of(
        new Target(
            PURPOSE,
            "webhook_endpoints",
            "current_secret_encrypted",
            CONTEXT_PREFIX,
            List.of("id")),
        new Target(
            PURPOSE,
            "webhook_endpoints",
            "previous_secret_encrypted",
            CONTEXT_PREFIX,
            List.of("id")));
  }

  private static String context(final UUID endpointId) {
    return CONTEXT_PREFIX + endpointId;
  }
}
