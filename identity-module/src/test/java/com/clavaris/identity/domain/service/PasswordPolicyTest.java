package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  @Test
  void rejectsNull() {
    assertThat(PasswordPolicy.isSatisfiedBy(null)).isFalse();
  }

  // SDE-III review, 2026-09-16: exercised against PasswordPolicy's own now-public MIN_LENGTH/
  // MAX_LENGTH constants (widened from private the same review — see that class's own comment)
  // instead of independently-maintained magic-number literals, so a future policy change can't
  // silently desync this boundary test from the rule it's actually testing.
  @Test
  void rejectsBelowMinimumLength() {
    assertThat(PasswordPolicy.isSatisfiedBy("a".repeat(PasswordPolicy.MIN_LENGTH - 1))).isFalse();
  }

  @Test
  void acceptsAtMinimumLength() {
    assertThat(PasswordPolicy.isSatisfiedBy("a".repeat(PasswordPolicy.MIN_LENGTH))).isTrue();
  }

  @Test
  void acceptsAboveMinimumLength() {
    assertThat(PasswordPolicy.isSatisfiedBy("a-reasonably-long-passphrase")).isTrue();
  }

  @Test
  void acceptsAtMaximumLength() {
    assertThat(PasswordPolicy.isSatisfiedBy("a".repeat(PasswordPolicy.MAX_LENGTH))).isTrue();
  }

  @Test
  void rejectsAboveMaximumLength() {
    // DoS defence against Argon2id's input-proportional hashing cost, not an arbitrary limit —
    // see PasswordPolicy's own class Javadoc.
    assertThat(PasswordPolicy.isSatisfiedBy("a".repeat(PasswordPolicy.MAX_LENGTH + 1))).isFalse();
  }

  @Test
  void rejectsAPathologicallyLongInput() {
    assertThat(PasswordPolicy.isSatisfiedBy("a".repeat(1_000_000))).isFalse();
  }
}
