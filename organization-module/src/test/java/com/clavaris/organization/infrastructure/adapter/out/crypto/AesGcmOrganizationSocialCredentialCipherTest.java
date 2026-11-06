package com.clavaris.organization.infrastructure.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.crypto.AesGcmCipher;
import com.clavaris.organization.domain.model.SocialProvider;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** TD-SEC-086 for the social-login client secrets: bound to Organization and provider. */
class AesGcmOrganizationSocialCredentialCipherTest {

  private static final UUID ORG = UUID.randomUUID();
  private static final SocialProvider GOOGLE = SocialProvider.GOOGLE;
  private static final SocialProvider GITHUB = SocialProvider.GITHUB;

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

  private static AesGcmOrganizationSocialCredentialCipher cipher(final String key) {
    return new AesGcmOrganizationSocialCredentialCipher(key, "1", "", providerOf(null));
  }

  // Flips one bit of the ciphertext itself. Editing a character of the base64 text is not enough:
  // the last characters can be padding bits that decode to the same bytes.
  private static String withFlippedBit(final String stored) {
    final int bodyStart = stored.indexOf('.', stored.indexOf('.') + 1) + 1;
    final byte[] body = Base64.getDecoder().decode(stored.substring(bodyStart));
    body[body.length - 1] ^= 0x01;
    return stored.substring(0, bodyStart) + Base64.getEncoder().encodeToString(body);
  }

  @Test
  void decryptReturnsExactlyWhatWasEncrypted() {
    AesGcmOrganizationSocialCredentialCipher cipher = cipher(randomKey());

    String encrypted = cipher.encrypt(ORG, GOOGLE, "my-raw-client-secret");

    assertThat(cipher.decrypt(ORG, GOOGLE, encrypted)).isEqualTo("my-raw-client-secret");
  }

  @Test
  void encryptingTheSameSecretTwiceProducesDifferentCiphertext_freshIvPerCall() {
    AesGcmOrganizationSocialCredentialCipher cipher = cipher(randomKey());

    String first = cipher.encrypt(ORG, GOOGLE, "same-secret");
    String second = cipher.encrypt(ORG, GOOGLE, "same-secret");

    assertThat(first).isNotEqualTo(second);
    assertThat(cipher.decrypt(ORG, GOOGLE, first)).isEqualTo("same-secret");
    assertThat(cipher.decrypt(ORG, GOOGLE, second)).isEqualTo("same-secret");
  }

  @Test
  void aTamperedCiphertextFailsToDecryptRatherThanReturningGarbage() {
    AesGcmOrganizationSocialCredentialCipher cipher = cipher(randomKey());
    String encrypted = cipher.encrypt(ORG, GOOGLE, "secret");
    String tampered = withFlippedBit(encrypted);

    assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(ORG, GOOGLE, tampered));
  }

  @Test
  void decryptingWithADifferentKeyFails() {
    AesGcmOrganizationSocialCredentialCipher cipherA = cipher(randomKey());
    AesGcmOrganizationSocialCredentialCipher cipherB = cipher(randomKey());
    String encryptedByA = cipherA.encrypt(ORG, GOOGLE, "secret");

    assertThatIllegalStateException().isThrownBy(() -> cipherB.decrypt(ORG, GOOGLE, encryptedByA));
  }

  // The attack: a secret copied into another Organization's row, or onto another provider's.
  @Test
  void aSecretCopiedToAnotherOrganizationOrProviderDoesNotDecrypt() {
    AesGcmOrganizationSocialCredentialCipher cipher = cipher(randomKey());
    String encrypted = cipher.encrypt(ORG, GOOGLE, "secret");
    UUID otherOrg = UUID.randomUUID();

    assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(otherOrg, GOOGLE, encrypted));
    assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(ORG, GITHUB, encrypted));
  }

  @Test
  void aKeyCanBeReplacedWhileOldSecretsKeepWorking() {
    String oldKey = randomKey();
    String old = cipher(oldKey).encrypt(ORG, GOOGLE, "old");
    AesGcmOrganizationSocialCredentialCipher rotated =
        new AesGcmOrganizationSocialCredentialCipher(
            randomKey(), "2", "1:" + oldKey, providerOf(null));

    assertThat(rotated.decrypt(ORG, GOOGLE, old)).isEqualTo("old");
    assertThat(rotated.encrypt(ORG, GOOGLE, "new")).startsWith("v1.2.");
  }

  @Test
  void aSecretStoredBeforeThisFormatStillReadsAndIsCounted() {
    String key = randomKey();
    String legacy = new AesGcmCipher(key).encrypt("old");
    SecurityMetricsRecorder recorder = mock(SecurityMetricsRecorder.class);
    AesGcmOrganizationSocialCredentialCipher cipher =
        new AesGcmOrganizationSocialCredentialCipher(key, "1", "", providerOf(recorder));

    assertThat(cipher.decrypt(ORG, GOOGLE, legacy)).isEqualTo("old");

    verify(recorder)
        .increment("clavaris.crypto.legacy_envelope_read", "store", "social-credential");
  }
}
