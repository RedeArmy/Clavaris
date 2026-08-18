package com.clavaris.identity.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * ADR-0005 / test-strategy.md §2 (security-specific): a change to hashing configuration must be
 * caught by a test asserting the actual behaviour, not just "it compiles" — a silently-weakened
 * parameter is otherwise invisible.
 */
class Argon2PasswordHasherTest {

  private final Argon2PasswordHasher hasher = new Argon2PasswordHasher();

  @Test
  void neverProducesTheSameHashTwiceForTheSamePassword() {
    // Salted — two accounts sharing a password must not be distinguishable by hash equality
    // alone from the stored data.
    String first = hasher.hash("a-shared-password");
    String second = hasher.hash("a-shared-password");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void theResultingHashActuallyVerifiesAgainstTheOriginalPasswordAndRejectsAWrongOne() {
    String hash = hasher.hash("correct-horse-battery-staple");
    Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    assertThat(encoder.matches("correct-horse-battery-staple", hash)).isTrue();
    assertThat(encoder.matches("wrong-password", hash)).isFalse();
  }

  @Test
  void producesAnArgon2idEncodedHash_notASilentlyDifferentVariant() {
    // ADR-0005 is specific about Argon2id, not "some Argon2 variant" — this is what would catch
    // a config regression to Argon2i/Argon2d.
    String hash = hasher.hash("some-password");

    assertThat(hash).startsWith("$argon2id$");
  }
}
