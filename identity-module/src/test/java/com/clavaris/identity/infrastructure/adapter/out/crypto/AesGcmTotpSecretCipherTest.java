package com.clavaris.identity.infrastructure.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.crypto.AesGcmCipher;
import com.clavaris.identity.domain.model.AccountId;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** TD-SEC-086 for the authenticator secrets: bound to the Account, rotatable, old rows readable. */
class AesGcmTotpSecretCipherTest {

  private static final String KEY = newKey();
  private static final String NEXT_KEY = newKey();

  private static String newKey() {
    final byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<SecurityMetricsRecorder> providerOf(
      final SecurityMetricsRecorder recorder) {
    final ObjectProvider<SecurityMetricsRecorder> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(recorder);
    return provider;
  }

  private static AesGcmTotpSecretCipher cipher(final SecurityMetricsRecorder recorder) {
    return new AesGcmTotpSecretCipher(KEY, "1", "", true, providerOf(recorder));
  }

  @Test
  void aSecretComesBackForItsOwnAccount() {
    final AccountId account = AccountId.newId();
    final AesGcmTotpSecretCipher cipher = cipher(null);

    assertThat(cipher.decrypt(account, cipher.encrypt(account, "JBSWY3DPEHPK3PXP")))
        .isEqualTo("JBSWY3DPEHPK3PXP");
  }

  // The attack: someone who can write the table gives an Account a second factor they know.
  @Test
  void aSecretCopiedIntoAnotherAccountsRowDoesNotDecrypt() {
    final AesGcmTotpSecretCipher cipher = cipher(null);
    final String theirs = cipher.encrypt(AccountId.newId(), "JBSWY3DPEHPK3PXP");
    final AccountId victim = AccountId.newId();

    assertThatThrownBy(() -> cipher.decrypt(victim, theirs))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aKeyCanBeReplacedWhileOldSecretsKeepWorking() {
    final AccountId account = AccountId.newId();
    final String old = cipher(null).encrypt(account, "OLD");
    final AesGcmTotpSecretCipher rotated =
        new AesGcmTotpSecretCipher(NEXT_KEY, "2", "1:" + KEY, true, providerOf(null));

    assertThat(rotated.decrypt(account, old)).isEqualTo("OLD");
    assertThat(rotated.encrypt(account, "NEW")).startsWith("v1.2.");
  }

  @Test
  void aSecretStoredBeforeThisFormatStillReadsAndIsCounted() {
    final AccountId account = AccountId.newId();
    final String legacy = new AesGcmCipher(KEY).encrypt("OLD");
    final SecurityMetricsRecorder recorder = mock(SecurityMetricsRecorder.class);
    final AesGcmTotpSecretCipher cipher = cipher(recorder);

    assertThat(cipher.decrypt(account, legacy)).isEqualTo("OLD");

    verify(recorder).increment("clavaris.crypto.legacy_envelope_read", "store", "totp-secret");
  }

  @Test
  void readingAnewSecretIsNotCounted() {
    final AccountId account = AccountId.newId();
    final SecurityMetricsRecorder recorder = mock(SecurityMetricsRecorder.class);
    final AesGcmTotpSecretCipher cipher = cipher(recorder);

    cipher.decrypt(account, cipher.encrypt(account, "NEW"));

    verifyNoInteractions(recorder);
  }
}
