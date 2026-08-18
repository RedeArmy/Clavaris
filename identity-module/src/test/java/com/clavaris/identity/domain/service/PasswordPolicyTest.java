package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  @Test
  void rejectsNull() {
    assertThat(PasswordPolicy.isSatisfiedBy(null)).isFalse();
  }

  @Test
  void rejectsBelowMinimumLength() {
    assertThat(PasswordPolicy.isSatisfiedBy("1234567")).isFalse(); // 7 chars
  }

  @Test
  void acceptsAtMinimumLength() {
    assertThat(PasswordPolicy.isSatisfiedBy("12345678")).isTrue(); // 8 chars
  }

  @Test
  void acceptsAboveMinimumLength() {
    assertThat(PasswordPolicy.isSatisfiedBy("a-reasonably-long-passphrase")).isTrue();
  }

  @Test
  void acceptsAtMaximumLength() {
    assertThat(PasswordPolicy.isSatisfiedBy("a".repeat(128))).isTrue();
  }

  @Test
  void rejectsAboveMaximumLength() {
    // DoS defence against Argon2id's input-proportional hashing cost, not an arbitrary limit —
    // see PasswordPolicy's own class Javadoc.
    assertThat(PasswordPolicy.isSatisfiedBy("a".repeat(129))).isFalse();
  }

  @Test
  void rejectsAPathologicallyLongInput() {
    assertThat(PasswordPolicy.isSatisfiedBy("a".repeat(1_000_000))).isFalse();
  }
}
