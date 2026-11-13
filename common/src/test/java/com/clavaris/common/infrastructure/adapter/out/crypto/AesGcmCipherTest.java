package com.clavaris.common.infrastructure.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The primitive under {@link EnvelopeCipher}. Mostly exercised through it; these cover the shapes
 * only a direct call reaches (TD-TEST-017, found by mutation testing).
 */
class AesGcmCipherTest {

  private static final String KEY = randomKey();

  private static String randomKey() {
    final byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  @Test
  void theOneArgumentFormRoundTripsAValueWithNoAssociatedData() {
    final AesGcmCipher cipher = new AesGcmCipher(KEY);

    assertThat(cipher.decrypt(cipher.encrypt("JBSWY3DPEHPK3PXP"))).isEqualTo("JBSWY3DPEHPK3PXP");
  }

  @Test
  void aValueEncryptedWithNoAssociatedDataDoesNotOpenWithSome() {
    final AesGcmCipher cipher = new AesGcmCipher(KEY);
    final String stored = cipher.encrypt("secret");

    assertThatThrownBy(() -> cipher.decrypt(stored, new byte[] {1}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("AES-GCM decryption failed");
  }

  @Test
  void textThatIsNotBase64IsAnUnreadableValueNotADifferentError() {
    final AesGcmCipher cipher = new AesGcmCipher(KEY);

    assertThatThrownBy(() -> cipher.decrypt("not base64 !!"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("AES-GCM decryption failed");
  }

  @Test
  void aValueNoLongerThanTheNonceIsUnreadable() {
    final AesGcmCipher cipher = new AesGcmCipher(KEY);
    final String onlyANonce = Base64.getEncoder().encodeToString(new byte[12]);

    assertThatThrownBy(() -> cipher.decrypt(onlyANonce))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("AES-GCM decryption failed");
  }
}
