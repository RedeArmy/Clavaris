package com.clavaris.organization.infrastructure.adapter.out.crypto;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.crypto.EnvelopeCipher;
import com.clavaris.common.infrastructure.adapter.out.crypto.SecretReencryptionStore;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Target;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialCipher;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implements {@link OrganizationSocialCredentialCipher} on the shared {@link EnvelopeCipher}
 * (common module): AES-256-GCM, a fresh 12-byte IV per encryption, the stored value naming the key
 * that encrypted it and bound to its Organization and provider (TD-SEC-086). It is the established
 * precedent for "a secret this system must present outbound in cleartext, encrypted at rest", the
 * same mechanics webhook-module's own cipher uses. Own dedicated key ({@code
 * SOCIAL_CREDENTIAL_ENCRYPTION_KEY}), deliberately not a reuse of the webhook or authenticator
 * keys: every at-rest secret gets its own independently rotatable key.
 *
 * <p>To rotate: set {@code clavaris.organization.social-credential-encryption-key-id} to a new id
 * with the new key, and put the old one in {@code
 * clavaris.organization.social-credential-previous-keys} as {@code id:base64}. A secret written
 * before key ids existed is read with the key called {@code 1}.
 */
@Component
class AesGcmOrganizationSocialCredentialCipher
    implements OrganizationSocialCredentialCipher, SecretReencryptionStore {

  private static final String PURPOSE = "social-credential";

  private final EnvelopeCipher cipher;

  // Package-private: constructed only by Spring's own component scan —
  // OrganizationSocialCredentialCipher (the port) is what every caller outside this package should
  // depend on.
  /* package */ AesGcmOrganizationSocialCredentialCipher(
      @Value("${clavaris.organization.social-credential-encryption-key}")
          final String base64EncodedKey,
      @Value("${clavaris.organization.social-credential-encryption-key-id:1}") final String keyId,
      @Value("${clavaris.organization.social-credential-previous-keys:}") final String previousKeys,
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
  public String encrypt(
      final UUID organizationId, final SocialProvider provider, final String rawClientSecret) {
    return cipher.encrypt(rawClientSecret, context(organizationId, provider));
  }

  @Override
  public String decrypt(
      final UUID organizationId,
      final SocialProvider provider,
      final String encryptedClientSecret) {
    return cipher.decrypt(encryptedClientSecret, context(organizationId, provider));
  }

  @Override
  public String store() {
    return PURPOSE;
  }

  @Override
  public EnvelopeCipher envelope() {
    return cipher;
  }

  @Override
  public List<Target> targets() {
    return List.of(
        new Target(
            PURPOSE,
            "organization_social_credentials",
            "client_secret_encrypted",
            "organization_social_credentials.client_secret_encrypted/",
            List.of("organization_id", "provider")));
  }

  private static String context(final UUID organizationId, final SocialProvider provider) {
    return "organization_social_credentials.client_secret_encrypted/"
        + organizationId
        + "/"
        + provider.name();
  }
}
