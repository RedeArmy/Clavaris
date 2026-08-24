package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * TD-SEC-023: dedicated unit coverage for the primitive {@link AntiAbuseRateLimitingFilter} builds
 * on — mirrors {@link BearerTokenHasherTest} exactly (same HMAC-SHA256-hex-digest shape, see {@link
 * RateLimitKeyHasher}'s own Javadoc for why this is a separate class/secret rather than a second
 * caller of {@link BearerTokenHasher}), asserting the two correctness properties that actually
 * matter for a Redis-key digest: reproducible for the same secret, and keyed rather than a plain
 * digest anyone could recompute from a stolen Redis backup alone.
 */
class RateLimitKeyHasherTest {

  @Test
  void producesTheSameDigestForTheSameValueAndSecret() {
    RateLimitKeyHasher hasher = new RateLimitKeyHasher("a-test-secret");

    assertThat(hasher.hash("user@example.com")).isEqualTo(hasher.hash("user@example.com"));
  }

  @Test
  void producesDifferentDigestsForDifferentValues() {
    RateLimitKeyHasher hasher = new RateLimitKeyHasher("a-test-secret");

    assertThat(hasher.hash("user-a@example.com")).isNotEqualTo(hasher.hash("user-b@example.com"));
  }

  @Test
  void producesDifferentDigestsForTheSameValueUnderDifferentSecrets() {
    // The property that actually makes this "keyed, not just hashed" (this class's own Javadoc):
    // knowing the algorithm and the raw identifier is not enough to reproduce the stored digest
    // without also knowing the secret — this is what makes an offline dictionary attack against a
    // compromised/leaked Redis keyspace infeasible.
    RateLimitKeyHasher hasherA = new RateLimitKeyHasher("secret-a");
    RateLimitKeyHasher hasherB = new RateLimitKeyHasher("secret-b");

    assertThat(hasherA.hash("user@example.com")).isNotEqualTo(hasherB.hash("user@example.com"));
  }

  @Test
  void producesA64CharacterLowercaseHexDigest() {
    // HMAC-SHA256 => 32 bytes => 64 hex characters.
    RateLimitKeyHasher hasher = new RateLimitKeyHasher("a-test-secret");

    String digest = hasher.hash("user@example.com");

    assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void rejectsABlankSecretAtConstructionRatherThanFailingLaterOnFirstUse(final String blankSecret) {
    assertThatThrownBy(() -> new RateLimitKeyHasher(blankSecret))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
