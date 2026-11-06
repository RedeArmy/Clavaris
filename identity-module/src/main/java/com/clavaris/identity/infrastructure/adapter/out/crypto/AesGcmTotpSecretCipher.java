package com.clavaris.identity.infrastructure.adapter.out.crypto;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.crypto.EnvelopeCipher;
import com.clavaris.common.infrastructure.adapter.out.crypto.SecretReencryptionStore;
import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Target;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpSecretCipher;
import com.clavaris.identity.domain.model.AccountId;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ADR-0040, TD-SEC-086: keys the authenticator secrets with their own {@code
 * TOTP_SECRET_ENCRYPTION_KEY}, not a reuse of the webhook or social-credential keys, so each
 * at-rest secret can be rotated alone. Each secret is bound to its Account (the table, column and
 * Account id are authenticated with it), so one Account's secret copied into another's row does not
 * decrypt, and the stored value names the key that encrypted it, so the key can be replaced without
 * stopping the service: set a new {@code ...-key-id} and put the old key in {@code
 * ...-previous-keys}.
 */
@Component
class AesGcmTotpSecretCipher implements TotpSecretCipher, SecretReencryptionStore {

  private static final String PURPOSE = "totp-secret";

  private final EnvelopeCipher cipher;

  /* package */ AesGcmTotpSecretCipher(
      @Value("${clavaris.identity.totp-secret-encryption-key}") final String base64EncodedKey,
      @Value("${clavaris.identity.totp-secret-encryption-key-id:1}") final String keyId,
      @Value("${clavaris.identity.totp-secret-previous-keys:}") final String previousKeys,
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
  public String encrypt(final AccountId accountId, final String plainSecret) {
    return cipher.encrypt(plainSecret, context(accountId));
  }

  @Override
  public String decrypt(final AccountId accountId, final String encryptedSecret) {
    return cipher.decrypt(encryptedSecret, context(accountId));
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
            "totp_credentials",
            "secret_encrypted",
            "totp_credentials.encrypted_secret/",
            List.of("account_id")));
  }

  private static String context(final AccountId accountId) {
    return "totp_credentials.encrypted_secret/" + accountId.value();
  }
}
