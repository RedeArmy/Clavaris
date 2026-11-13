package com.clavaris.common.infrastructure.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** TD-SEC-086: bound to its row, rotatable, and still reading what was written before. */
class EnvelopeCipherTest {

  private static final String KEY_ONE = newKey();
  private static final String KEY_TWO = newKey();
  private static final String ROW_A = "totp_credentials.encrypted_secret/account-a";
  private static final String ROW_B = "totp_credentials.encrypted_secret/account-b";

  private static String newKey() {
    final byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static EnvelopeCipher cipher() {
    return new EnvelopeCipher("totp-secret", "1", KEY_ONE, "");
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
  void aValueComesBackThroughItsOwnRow() {
    final String stored = cipher().encrypt("JBSWY3DPEHPK3PXP", ROW_A);

    assertThat(cipher().decrypt(stored, ROW_A)).isEqualTo("JBSWY3DPEHPK3PXP");
  }

  @Test
  void theStoredValueNamesItsFormatAndKeyAndIsNotThePlaintext() {
    final String stored = cipher().encrypt("JBSWY3DPEHPK3PXP", ROW_A);

    assertThat(stored).startsWith("v1.1.").doesNotContain("JBSWY3DPEHPK3PXP");
  }

  @Test
  void twoEncryptionsOfTheSameValueDiffer() {
    assertThat(cipher().encrypt("same", ROW_A)).isNotEqualTo(cipher().encrypt("same", ROW_A));
  }

  // The attack this exists for: someone who can write to the table copies one row's secret into
  // another's, to make an Account's second factor one they know.
  @Test
  void storedTextThatIsNotBase64IsUnreadableNotAnotherKindOfFailure() {
    // Callers catch IllegalStateException for an unreadable secret; base64 text that was damaged
    // must not escape as an IllegalArgumentException and turn a sign-in into a 500.
    final EnvelopeCipher cipher = cipher();

    assertThatThrownBy(() -> cipher.decrypt("v1.1.not base64 !!", ROW_A))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.decrypt("not base64 !!", ROW_A))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reencryptingMovesAValueToTheActiveKeyAndTheOldKeyIsNoLongerNeeded() {
    final String underOld = cipher().encrypt("JBSWY3DPEHPK3PXP", ROW_A);
    final EnvelopeCipher rotated = new EnvelopeCipher("totp-secret", "2", KEY_TWO, "1:" + KEY_ONE);
    assertThat(rotated.isCurrent(underOld)).isFalse();

    final String moved = rotated.reencrypt(underOld, ROW_A);

    assertThat(rotated.isCurrent(moved)).isTrue();
    final EnvelopeCipher onlyTheNewKey = new EnvelopeCipher("totp-secret", "2", KEY_TWO, "");
    assertThat(onlyTheNewKey.decrypt(moved, ROW_A)).isEqualTo("JBSWY3DPEHPK3PXP");
  }

  @Test
  void reencryptingAValueInTheOldFormatBindsItToItsRowWithoutCountingARead() {
    final int[] reads = {0};
    final EnvelopeCipher observed =
        new EnvelopeCipher("totp-secret", "1", KEY_ONE, "", () -> reads[0]++);
    final String legacy = new AesGcmCipher(KEY_ONE).encrypt("JBSWY3DPEHPK3PXP");

    final String moved = observed.reencrypt(legacy, ROW_A);

    assertThat(reads[0]).isZero();
    assertThat(observed.isLegacy(moved)).isFalse();
    assertThat(observed.decrypt(moved, ROW_A)).isEqualTo("JBSWY3DPEHPK3PXP");
    assertThatThrownBy(() -> observed.decrypt(moved, ROW_B))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reencryptingAValueOfAnotherRowIsRefused() {
    final EnvelopeCipher cipher = cipher();
    final String stored = cipher.encrypt("JBSWY3DPEHPK3PXP", ROW_A);

    assertThatThrownBy(() -> cipher.reencrypt(stored, ROW_B))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aValueCopiedIntoAnotherRowDoesNotDecrypt() {
    final String stored = cipher().encrypt("JBSWY3DPEHPK3PXP", ROW_A);
    final EnvelopeCipher cipher = cipher();

    assertThatThrownBy(() -> cipher.decrypt(stored, ROW_B))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("AES-GCM decryption failed");
  }

  @Test
  void aValueFromAnotherStoreDoesNotDecryptEvenUnderTheSameKeyAndContext() {
    final String stored =
        new EnvelopeCipher("webhook-signing-secret", "1", KEY_ONE, "").encrypt("secret", ROW_A);
    final EnvelopeCipher cipher = cipher();

    assertThatThrownBy(() -> cipher.decrypt(stored, ROW_A))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aTamperedValueDoesNotDecrypt() {
    final String stored = cipher().encrypt("JBSWY3DPEHPK3PXP", ROW_A);
    final String tampered = withFlippedBit(stored);
    final EnvelopeCipher cipher = cipher();

    assertThatThrownBy(() -> cipher.decrypt(tampered, ROW_A))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rotatingTheKeyKeepsOldValuesReadableAndWritesNewOnesUnderTheNewKey() {
    final String old = cipher().encrypt("old secret", ROW_A);
    final EnvelopeCipher rotated = new EnvelopeCipher("totp-secret", "2", KEY_TWO, "1:" + KEY_ONE);

    assertThat(rotated.decrypt(old, ROW_A)).isEqualTo("old secret");
    final String fresh = rotated.encrypt("new secret", ROW_A);
    assertThat(fresh).startsWith("v1.2.");
    assertThat(rotated.decrypt(fresh, ROW_A)).isEqualTo("new secret");
  }

  @Test
  void onceTheOldKeyIsRemovedItsValuesSayWhichKeyIsMissing() {
    final String old = cipher().encrypt("old secret", ROW_A);
    final EnvelopeCipher withoutOld = new EnvelopeCipher("totp-secret", "2", KEY_TWO, "");

    assertThatThrownBy(() -> withoutOld.decrypt(old, ROW_A))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("key '1'")
        .hasMessageContaining("previous key");
  }

  @Test
  void aValueWrittenBeforeThisFormatIsStillReadAndIsReportedAsLegacy() {
    final String legacy = new AesGcmCipher(KEY_ONE).encrypt("written long ago");

    assertThat(cipher().isLegacy(legacy)).isTrue();
    assertThat(cipher().decrypt(legacy, ROW_A)).isEqualTo("written long ago");
    assertThat(cipher().isLegacy(cipher().encrypt("x", ROW_A))).isFalse();
  }

  @Test
  void aLegacyValueIsReadWithTheKeyCalledOneEvenAfterARotation() {
    final String legacy = new AesGcmCipher(KEY_ONE).encrypt("written long ago");
    final EnvelopeCipher rotated = new EnvelopeCipher("totp-secret", "2", KEY_TWO, "1:" + KEY_ONE);

    assertThat(rotated.decrypt(legacy, ROW_A)).isEqualTo("written long ago");
  }

  @Test
  void aKeyThatIsNot256BitsIsRefusedWithoutEchoingIt() {
    final String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> new EnvelopeCipher("totp-secret", "1", shortKey, ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("256 bits")
        .hasMessageContaining("128 bits")
        .hasMessageNotContaining(shortKey);
    assertThatThrownBy(() -> new EnvelopeCipher("totp-secret", "1", "not base64!!", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base64");
  }

  @Test
  void keyIdsAndPreviousKeysAreValidated() {
    assertThatThrownBy(() -> new EnvelopeCipher("p", "a.b", KEY_ONE, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EnvelopeCipher("p", "", KEY_ONE, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EnvelopeCipher("p", "1", KEY_ONE, "1:" + KEY_TWO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("more than once");
    assertThatThrownBy(() -> new EnvelopeCipher("p", "1", KEY_ONE, KEY_TWO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id:base64");
  }

  @Test
  void aBlankContextIsRefusedBecauseItWouldBindNothing() {
    final EnvelopeCipher cipher = cipher();

    assertThatThrownBy(() -> cipher.encrypt("x", " ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void eachReadOfAnOldFormatValueIsReportedAndNewOnesAreNot() {
    final int[] reads = {0};
    final EnvelopeCipher observed =
        new EnvelopeCipher("totp-secret", "1", KEY_ONE, "", () -> reads[0]++);
    final String legacy = new AesGcmCipher(KEY_ONE).encrypt("old");

    observed.decrypt(legacy, ROW_A);
    observed.decrypt(legacy, ROW_A);
    observed.decrypt(observed.encrypt("new", ROW_A), ROW_A);

    assertThat(reads[0]).isEqualTo(2);
  }

  // TD-SEC-090: the switch an operator flips once the legacy-read counter has stayed at zero.
  @Test
  void withLegacyReadSwitchedOffAnOldFormatValueIsRefusedAndCurrentOnesStillWork() {
    final EnvelopeCipher strict = new EnvelopeCipher("totp-secret", "1", KEY_ONE, "", null, false);
    final String legacy = new AesGcmCipher(KEY_ONE).encrypt("old");
    final String current = strict.encrypt("new", ROW_A);

    assertThat(strict.decrypt(current, ROW_A)).isEqualTo("new");
    assertThatThrownBy(() -> strict.decrypt(legacy, ROW_A))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accept-legacy-envelope");
    assertThatThrownBy(() -> strict.reencrypt(legacy, ROW_A))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accept-legacy-envelope");
  }

  @Test
  void aRefusedLegacyValueIsStillCountedSoTheOperatorSeesItWasThere() {
    final int[] reads = {0};
    final EnvelopeCipher strict =
        new EnvelopeCipher("totp-secret", "1", KEY_ONE, "", () -> reads[0]++, false);
    final String legacy = new AesGcmCipher(KEY_ONE).encrypt("old");

    assertThatThrownBy(() -> strict.decrypt(legacy, ROW_A))
        .isInstanceOf(IllegalStateException.class);

    assertThat(reads[0]).isEqualTo(1);
  }

  @Test
  void legacyReadStaysOnByDefault() {
    final String legacy = new AesGcmCipher(KEY_ONE).encrypt("old");

    assertThat(cipher().decrypt(legacy, ROW_A)).isEqualTo("old");
  }

  // TD-TEST-017 (PIT): a previous key with an empty id is refused for its id, not for its shape.
  @Test
  void aPreviousKeyWithAnEmptyIdIsRefusedAsABadKeyId() {
    assertThatThrownBy(() -> new EnvelopeCipher("p", "2", KEY_ONE, ":" + KEY_TWO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key id");
  }
}
