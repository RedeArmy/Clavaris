package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Dedicated unit coverage for the primitive {@link IdempotencyKeyFilter} builds on — mirrors {@link
 * RateLimitKeyHasherTest} exactly (same HMAC-SHA256-hex-digest shape), asserting the two
 * correctness properties that actually matter for a Redis-key digest: reproducible for the same
 * secret, and keyed rather than a plain digest anyone could recompute from a stolen Redis backup
 * alone.
 */
class IdempotencyKeyHasherTest {

  @Test
  void producesTheSameDigestForTheSameValueAndSecret() {
    IdempotencyKeyHasher hasher = new IdempotencyKeyHasher("a-test-secret");

    assertThat(hasher.hash("client-a:order-123")).isEqualTo(hasher.hash("client-a:order-123"));
  }

  @Test
  void producesDifferentDigestsForDifferentValues() {
    IdempotencyKeyHasher hasher = new IdempotencyKeyHasher("a-test-secret");

    assertThat(hasher.hash("client-a:order-123")).isNotEqualTo(hasher.hash("client-a:order-456"));
  }

  @Test
  void producesDifferentDigestsForTheSameValueUnderDifferentSecrets() {
    IdempotencyKeyHasher hasherA = new IdempotencyKeyHasher("secret-a");
    IdempotencyKeyHasher hasherB = new IdempotencyKeyHasher("secret-b");

    assertThat(hasherA.hash("client-a:order-123")).isNotEqualTo(hasherB.hash("client-a:order-123"));
  }

  @Test
  void producesA64CharacterLowercaseHexDigest() {
    // HMAC-SHA256 => 32 bytes => 64 hex characters.
    IdempotencyKeyHasher hasher = new IdempotencyKeyHasher("a-test-secret");

    String digest = hasher.hash("client-a:order-123");

    assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void rejectsABlankSecretAtConstructionRatherThanFailingLaterOnFirstUse(final String blankSecret) {
    assertThatThrownBy(() -> new IdempotencyKeyHasher(blankSecret))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
