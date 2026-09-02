package com.clavaris.webhook.infrastructure.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmWebhookSigningSecretCipherTest {

  private static String randomKey() {
    byte[] keyBytes = new byte[32];
    new SecureRandom().nextBytes(keyBytes);
    return Base64.getEncoder().encodeToString(keyBytes);
  }

  @Test
  void decryptReturnsExactlyWhatWasEncrypted() {
    AesGcmWebhookSigningSecretCipher cipher = new AesGcmWebhookSigningSecretCipher(randomKey());

    String encrypted = cipher.encrypt("my-raw-signing-secret");

    assertThat(cipher.decrypt(encrypted)).isEqualTo("my-raw-signing-secret");
  }

  @Test
  void encryptingTheSameSecretTwiceProducesDifferentCiphertext_freshIvPerCall() {
    AesGcmWebhookSigningSecretCipher cipher = new AesGcmWebhookSigningSecretCipher(randomKey());

    String first = cipher.encrypt("same-secret");
    String second = cipher.encrypt("same-secret");

    assertThat(first).isNotEqualTo(second);
    // Both still decrypt back to the same plaintext despite the different ciphertext.
    assertThat(cipher.decrypt(first)).isEqualTo("same-secret");
    assertThat(cipher.decrypt(second)).isEqualTo("same-secret");
  }

  @Test
  void aTamperedCiphertextFailsToDecryptRatherThanReturningGarbage() {
    AesGcmWebhookSigningSecretCipher cipher = new AesGcmWebhookSigningSecretCipher(randomKey());
    String encrypted = cipher.encrypt("secret");
    byte[] raw = Base64.getDecoder().decode(encrypted);
    raw[raw.length - 1] ^= 0x01; // flip the last byte of the auth-tag-bearing ciphertext
    String tampered = Base64.getEncoder().encodeToString(raw);

    assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(tampered));
  }

  @Test
  void decryptingWithADifferentKeyFails() {
    AesGcmWebhookSigningSecretCipher cipherA = new AesGcmWebhookSigningSecretCipher(randomKey());
    AesGcmWebhookSigningSecretCipher cipherB = new AesGcmWebhookSigningSecretCipher(randomKey());
    String encryptedByA = cipherA.encrypt("secret");

    assertThatIllegalStateException().isThrownBy(() -> cipherB.decrypt(encryptedByA));
  }
}
