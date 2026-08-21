package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefreshTokenSecretTest {

  @Test
  void generateRawValueProducesNonBlankGenuinelyRandomValues() {
    String first = RefreshTokenSecret.generateRawValue();
    String second = RefreshTokenSecret.generateRawValue();

    assertThat(first).isNotBlank();
    assertThat(second).isNotBlank();
    assertThat(first).isNotEqualTo(second);
    // 256 bits, base64url without padding — a fixed length is itself a property worth pinning so a
    // future accidental entropy reduction doesn't go unnoticed.
    assertThat(first).hasSize(43);
  }

  @Test
  void hashIsDeterministicForTheSameRawValue() {
    String rawValue = RefreshTokenSecret.generateRawValue();

    assertThat(RefreshTokenSecret.hash(rawValue)).isEqualTo(RefreshTokenSecret.hash(rawValue));
  }

  @Test
  void hashNeverReturnsTheRawValueItself() {
    // BR-DATA-01/data-model.md §2: hash-not-plaintext — a hash that ever equals its own input
    // would defeat the entire point of hashing it.
    String rawValue = RefreshTokenSecret.generateRawValue();

    assertThat(RefreshTokenSecret.hash(rawValue)).isNotEqualTo(rawValue);
  }

  @Test
  void differentRawValuesProduceDifferentHashes() {
    String first = RefreshTokenSecret.generateRawValue();
    String second = RefreshTokenSecret.generateRawValue();

    assertThat(RefreshTokenSecret.hash(first)).isNotEqualTo(RefreshTokenSecret.hash(second));
  }

  @Test
  void hashIsLowercaseHexOfTheExpectedSha256Length() {
    String hash = RefreshTokenSecret.hash("any-value");

    assertThat(hash).hasSize(64); // SHA-256: 32 bytes -> 64 hex characters
    assertThat(hash).matches("[0-9a-f]{64}");
  }
}
