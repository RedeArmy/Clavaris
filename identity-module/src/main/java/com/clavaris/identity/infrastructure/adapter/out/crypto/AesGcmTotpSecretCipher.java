package com.clavaris.identity.infrastructure.adapter.out.crypto;

import com.clavaris.common.infrastructure.adapter.out.crypto.AesGcmCipher;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpSecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ADR-0040: keys the authenticator secrets with their own {@code TOTP_SECRET_ENCRYPTION_KEY}, not a
 * reuse of the webhook or social-credential keys, so each at-rest secret can be rotated alone.
 */
@Component
class AesGcmTotpSecretCipher implements TotpSecretCipher {

  private final AesGcmCipher cipher;

  /* package */ AesGcmTotpSecretCipher(
      @Value("${clavaris.identity.totp-secret-encryption-key}") final String base64EncodedKey) {
    this.cipher = new AesGcmCipher(base64EncodedKey);
  }

  @Override
  public String encrypt(final String plainSecret) {
    return cipher.encrypt(plainSecret);
  }

  @Override
  public String decrypt(final String encryptedSecret) {
    return cipher.decrypt(encryptedSecret);
  }
}
