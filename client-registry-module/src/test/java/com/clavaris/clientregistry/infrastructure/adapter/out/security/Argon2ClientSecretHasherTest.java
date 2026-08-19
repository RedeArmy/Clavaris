package com.clavaris.clientregistry.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * ADR-0005 / test-strategy.md §2 (security-specific): same discipline as identity-module's own
 * {@code Argon2PasswordHasherTest} — a silently-weakened hashing parameter must be caught by a test
 * asserting the actual behaviour, arguably even higher-stakes here (CLAUDE.md §5: this hashes the
 * single highest-value credential in the system).
 */
class Argon2ClientSecretHasherTest {

  private final Argon2ClientSecretHasher hasher = new Argon2ClientSecretHasher();

  @Test
  void neverProducesTheSameHashTwiceForTheSameSecret() {
    String first = hasher.hash("a-shared-secret");
    String second = hasher.hash("a-shared-secret");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void theResultingHashActuallyVerifiesAgainstTheOriginalSecretAndRejectsAWrongOne() {
    String hash = hasher.hash("the-real-bootstrap-secret");
    Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    assertThat(encoder.matches("the-real-bootstrap-secret", hash)).isTrue();
    assertThat(encoder.matches("a-wrong-secret", hash)).isFalse();
  }

  @Test
  void producesAnArgon2idEncodedHash_notASilentlyDifferentVariant() {
    // ADR-0005 is specific about Argon2id, not "some Argon2 variant" — this is what would catch
    // a config regression to Argon2i/Argon2d.
    String hash = hasher.hash("some-secret");

    assertThat(hash).startsWith("$argon2id$");
  }
}
