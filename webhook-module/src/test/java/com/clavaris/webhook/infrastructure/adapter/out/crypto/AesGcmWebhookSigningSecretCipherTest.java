package com.clavaris.webhook.infrastructure.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.crypto.AesGcmCipher;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** TD-SEC-086 for the webhook signing secrets: bound to the endpoint, rotatable. */
class AesGcmWebhookSigningSecretCipherTest {

  private static final UUID ENDPOINT = UUID.randomUUID();

  private static String randomKey() {
    byte[] keyBytes = new byte[32];
    new SecureRandom().nextBytes(keyBytes);
    return Base64.getEncoder().encodeToString(keyBytes);
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<SecurityMetricsRecorder> providerOf(
      final SecurityMetricsRecorder recorder) {
    final ObjectProvider<SecurityMetricsRecorder> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(recorder);
    return provider;
  }

  private static AesGcmWebhookSigningSecretCipher cipher(final String key) {
    return new AesGcmWebhookSigningSecretCipher(key, "1", "", providerOf(null));
  }

  // Flips one bit of the ciphertext itself; editing a base64 character can hit padding bits.
  private static String withFlippedBit(final String stored) {
    final int bodyStart = stored.indexOf('.', stored.indexOf('.') + 1) + 1;
    final byte[] body = Base64.getDecoder().decode(stored.substring(bodyStart));
    body[body.length - 1] ^= 0x01;
    return stored.substring(0, bodyStart) + Base64.getEncoder().encodeToString(body);
  }

  @Test
  void decryptReturnsExactlyWhatWasEncrypted() {
    AesGcmWebhookSigningSecretCipher cipher = cipher(randomKey());

    String encrypted = cipher.encrypt(ENDPOINT, "my-raw-signing-secret");

    assertThat(cipher.decrypt(ENDPOINT, encrypted)).isEqualTo("my-raw-signing-secret");
  }

  @Test
  void encryptingTheSameSecretTwiceProducesDifferentCiphertext_freshIvPerCall() {
    AesGcmWebhookSigningSecretCipher cipher = cipher(randomKey());

    String first = cipher.encrypt(ENDPOINT, "same-secret");
    String second = cipher.encrypt(ENDPOINT, "same-secret");

    assertThat(first).isNotEqualTo(second);
    // Both still decrypt back to the same plaintext despite the different ciphertext.
    assertThat(cipher.decrypt(ENDPOINT, first)).isEqualTo("same-secret");
    assertThat(cipher.decrypt(ENDPOINT, second)).isEqualTo("same-secret");
  }

  @Test
  void aTamperedCiphertextFailsToDecryptRatherThanReturningGarbage() {
    AesGcmWebhookSigningSecretCipher cipher = cipher(randomKey());
    String tampered = withFlippedBit(cipher.encrypt(ENDPOINT, "secret"));

    assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(ENDPOINT, tampered));
  }

  @Test
  void decryptingWithADifferentKeyFails() {
    AesGcmWebhookSigningSecretCipher cipherA = cipher(randomKey());
    AesGcmWebhookSigningSecretCipher cipherB = cipher(randomKey());
    String encryptedByA = cipherA.encrypt(ENDPOINT, "secret");

    assertThatIllegalStateException().isThrownBy(() -> cipherB.decrypt(ENDPOINT, encryptedByA));
  }

  // The attack: a consumer's endpoint pointed at a signing secret someone else chose.
  @Test
  void aSecretCopiedToAnotherEndpointDoesNotDecrypt() {
    AesGcmWebhookSigningSecretCipher cipher = cipher(randomKey());
    String encrypted = cipher.encrypt(ENDPOINT, "secret");
    UUID other = UUID.randomUUID();

    assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(other, encrypted));
  }

  @Test
  void aKeyCanBeReplacedWhileOldSecretsKeepWorking() {
    String oldKey = randomKey();
    String old = cipher(oldKey).encrypt(ENDPOINT, "old");
    AesGcmWebhookSigningSecretCipher rotated =
        new AesGcmWebhookSigningSecretCipher(randomKey(), "2", "1:" + oldKey, providerOf(null));

    assertThat(rotated.decrypt(ENDPOINT, old)).isEqualTo("old");
    assertThat(rotated.encrypt(ENDPOINT, "new")).startsWith("v1.2.");
  }

  @Test
  void aSecretStoredBeforeThisFormatStillReadsAndIsCounted() {
    String key = randomKey();
    String legacy = new AesGcmCipher(key).encrypt("old");
    SecurityMetricsRecorder recorder = mock(SecurityMetricsRecorder.class);
    AesGcmWebhookSigningSecretCipher cipher =
        new AesGcmWebhookSigningSecretCipher(key, "1", "", providerOf(recorder));

    assertThat(cipher.decrypt(ENDPOINT, legacy)).isEqualTo("old");

    verify(recorder)
        .increment("clavaris.crypto.legacy_envelope_read", "store", "webhook-signing-secret");
  }
}
