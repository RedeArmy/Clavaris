package com.clavaris.clientregistry.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class HighEntropyClientSecretTest {

  // 43 characters, the length of a 256-bit secret in unpadded URL-safe Base64.
  private static final String SECRET = "k3Jx0dQb9vYwPz2mNfRt7LaUcHsE5oGiTnBqXyVhZ4A";

  @Test
  void hashesToTheKnownSha256OfTheSecretUnderItsPrefix() {
    // sha256("k3Jx0dQb9vYwPz2mNfRt7LaUcHsE5oGiTnBqXyVhZ4A"), unpadded URL-safe Base64: pins the
    // scheme itself, so a change of algorithm or encoding is a deliberate act that breaks this.
    assertThat(HighEntropyClientSecret.hash(SECRET))
        .isEqualTo("{sha256}" + expectedDigestOf(SECRET));
  }

  @Test
  void theSameSecretAlwaysHashesTheSame() {
    assertThat(HighEntropyClientSecret.hash(SECRET))
        .isEqualTo(HighEntropyClientSecret.hash(SECRET));
  }

  @Test
  void aPresentedSecretMatchesItsOwnHash() {
    assertThat(HighEntropyClientSecret.matches(SECRET, HighEntropyClientSecret.hash(SECRET)))
        .isTrue();
  }

  @Test
  void anyOtherSecretDoesNotMatch() {
    final String stored = HighEntropyClientSecret.hash(SECRET);

    assertThat(HighEntropyClientSecret.matches(SECRET + "x", stored)).isFalse();
    assertThat(HighEntropyClientSecret.matches(SECRET.substring(1), stored)).isFalse();
    assertThat(HighEntropyClientSecret.matches("", stored)).isFalse();
    assertThat(HighEntropyClientSecret.matches(SECRET.toUpperCase(), stored)).isFalse();
  }

  @Test
  void aHashOfThisSchemeIsRecognisedAndAnArgon2HashIsNot() {
    assertThat(HighEntropyClientSecret.isHash(HighEntropyClientSecret.hash(SECRET))).isTrue();
    assertThat(HighEntropyClientSecret.isHash("$argon2id$v=19$m=16384,t=2,p=1$abc$def")).isFalse();
    assertThat(HighEntropyClientSecret.isHash(null)).isFalse();
    assertThat(HighEntropyClientSecret.isHash("")).isFalse();
  }

  // Safety net, not a guarantee: a stored hash that is not of this scheme can never be satisfied by
  // it, so a legacy Argon2 value never accidentally authenticates through the cheap path.
  @Test
  void aStoredValueOfAnotherSchemeNeverMatches() {
    assertThat(HighEntropyClientSecret.matches(SECRET, "$argon2id$v=19$m=16384,t=2,p=1$abc$def"))
        .isFalse();
    assertThat(HighEntropyClientSecret.matches(SECRET, SECRET)).isFalse();
    assertThat(HighEntropyClientSecret.matches(SECRET, null)).isFalse();
    assertThat(HighEntropyClientSecret.matches(null, HighEntropyClientSecret.hash(SECRET)))
        .isFalse();
  }

  // The rail that keeps an operator- or user-chosen secret off the cheap path.
  @Test
  void aSecretShortEnoughToHaveBeenTypedByAPersonIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HighEntropyClientSecret.hash("a-test-platform-secret"))
        .withMessageContaining("Argon2");
    assertThatIllegalArgumentException().isThrownBy(() -> HighEntropyClientSecret.hash(""));
    assertThatIllegalArgumentException().isThrownBy(() -> HighEntropyClientSecret.hash(null));
  }

  @Test
  void theShortestAcceptedSecretIsExactlyTheDocumentedMinimum() {
    final String atMinimum = "x".repeat(HighEntropyClientSecret.MIN_LENGTH);

    assertThat(HighEntropyClientSecret.hash(atMinimum)).startsWith("{sha256}");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> HighEntropyClientSecret.hash(atMinimum.substring(1)));
  }

  private static String expectedDigestOf(final String secret) {
    try {
      return java.util.Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
