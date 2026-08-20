package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * ADR-0005 / test-strategy.md §2 (security-specific): same bar as {@link Argon2PasswordHasherTest}
 * — a real round trip through the actual hasher this verifier must interoperate with, not a mocked
 * encoder that could silently drift from what {@code Argon2PasswordHasher} actually produces.
 */
class Argon2PasswordVerifierTest {

  private final Argon2PasswordHasher hasher = new Argon2PasswordHasher();
  private final Argon2PasswordVerifier verifier = new Argon2PasswordVerifier();

  @Test
  void matchesTheCorrectPasswordAgainstAHashProducedByTheRealHasher() {
    String hash = hasher.hash("correct-horse-battery-staple");

    assertThat(verifier.matches("correct-horse-battery-staple", hash)).isTrue();
  }

  @Test
  void rejectsAWrongPassword() {
    String hash = hasher.hash("correct-horse-battery-staple");

    assertThat(verifier.matches("a-different-password", hash)).isFalse();
  }
}
