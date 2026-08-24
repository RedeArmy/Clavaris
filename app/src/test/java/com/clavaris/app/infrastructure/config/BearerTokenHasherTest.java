package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * TD-SEC-019: dedicated unit coverage for the primitive {@link
 * HashedTokenOAuth2AuthorizationService} builds on — a lookup-by-value hash has exactly two
 * correctness properties that matter (reproducible for the same secret, and actually keyed rather
 * than a plain digest anyone could recompute from a stolen backup alone), both asserted directly
 * here rather than only incidentally through the wrapper's own tests.
 */
class BearerTokenHasherTest {

  @Test
  void producesTheSameDigestForTheSameValueAndSecret() {
    BearerTokenHasher hasher = new BearerTokenHasher("a-test-secret");

    assertThat(hasher.hash("some-raw-token-value")).isEqualTo(hasher.hash("some-raw-token-value"));
  }

  @Test
  void producesDifferentDigestsForDifferentValues() {
    BearerTokenHasher hasher = new BearerTokenHasher("a-test-secret");

    assertThat(hasher.hash("token-a")).isNotEqualTo(hasher.hash("token-b"));
  }

  @Test
  void producesDifferentDigestsForTheSameValueUnderDifferentSecrets() {
    // The property that actually makes this "keyed, not just hashed" (this class's own Javadoc):
    // knowing the algorithm and the raw value is not enough to reproduce the stored digest without
    // also knowing the secret — this is what makes an offline dictionary attack against a
    // compromised Postgres backup alone infeasible.
    BearerTokenHasher hasherA = new BearerTokenHasher("secret-a");
    BearerTokenHasher hasherB = new BearerTokenHasher("secret-b");

    assertThat(hasherA.hash("some-raw-token-value"))
        .isNotEqualTo(hasherB.hash("some-raw-token-value"));
  }

  @Test
  void producesA64CharacterLowercaseHexDigest() {
    // HMAC-SHA256 => 32 bytes => 64 hex characters — asserted explicitly since
    // JdbcOAuth2AuthorizationService's own oauth2_authorization columns are fixed-width VARCHARs
    // (data-model.md §2); a silently different encoding here would only surface as a truncated-
    // value bug much later.
    BearerTokenHasher hasher = new BearerTokenHasher("a-test-secret");

    String digest = hasher.hash("some-raw-token-value");

    assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void rejectsABlankSecretAtConstructionRatherThanFailingLaterOnFirstUse(final String blankSecret) {
    assertThatThrownBy(() -> new BearerTokenHasher(blankSecret))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
