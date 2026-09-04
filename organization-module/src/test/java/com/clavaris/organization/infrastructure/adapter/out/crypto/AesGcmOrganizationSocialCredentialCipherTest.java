package com.clavaris.organization.infrastructure.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmOrganizationSocialCredentialCipherTest {

  private static String randomKey() {
    byte[] keyBytes = new byte[32];
    new SecureRandom().nextBytes(keyBytes);
    return Base64.getEncoder().encodeToString(keyBytes);
  }

  @Test
  void decryptReturnsExactlyWhatWasEncrypted() {
    AesGcmOrganizationSocialCredentialCipher cipher =
        new AesGcmOrganizationSocialCredentialCipher(randomKey());

    String encrypted = cipher.encrypt("my-raw-client-secret");

    assertThat(cipher.decrypt(encrypted)).isEqualTo("my-raw-client-secret");
  }

  @Test
  void encryptingTheSameSecretTwiceProducesDifferentCiphertext_freshIvPerCall() {
    AesGcmOrganizationSocialCredentialCipher cipher =
        new AesGcmOrganizationSocialCredentialCipher(randomKey());

    String first = cipher.encrypt("same-secret");
    String second = cipher.encrypt("same-secret");

    assertThat(first).isNotEqualTo(second);
    assertThat(cipher.decrypt(first)).isEqualTo("same-secret");
    assertThat(cipher.decrypt(second)).isEqualTo("same-secret");
  }

  @Test
  void aTamperedCiphertextFailsToDecryptRatherThanReturningGarbage() {
    AesGcmOrganizationSocialCredentialCipher cipher =
        new AesGcmOrganizationSocialCredentialCipher(randomKey());
    String encrypted = cipher.encrypt("secret");
    byte[] raw = Base64.getDecoder().decode(encrypted);
    raw[raw.length - 1] ^= 0x01;
    String tampered = Base64.getEncoder().encodeToString(raw);

    assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(tampered));
  }

  @Test
  void decryptingWithADifferentKeyFails() {
    AesGcmOrganizationSocialCredentialCipher cipherA =
        new AesGcmOrganizationSocialCredentialCipher(randomKey());
    AesGcmOrganizationSocialCredentialCipher cipherB =
        new AesGcmOrganizationSocialCredentialCipher(randomKey());
    String encryptedByA = cipherA.encrypt("secret");

    assertThatIllegalStateException().isThrownBy(() -> cipherB.decrypt(encryptedByA));
  }
}
