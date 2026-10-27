package com.clavaris.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConfirmationTokenCodecTest {

  private final Instant now = Instant.parse("2026-10-26T12:00:00Z");

  @Test
  void aFreshMatchingTokenIsAccepted() {
    assertThat(
            ConfirmationTokenCodec.matches(
                ConfirmationTokenCodec.encode("abc", now.plusSeconds(60)), "abc", now))
        .isTrue();
  }

  @Test
  void anExpiredTokenIsRejected() {
    assertThat(
            ConfirmationTokenCodec.matches(
                ConfirmationTokenCodec.encode("abc", now.minusSeconds(1)), "abc", now))
        .isFalse();
  }

  @Test
  void aDifferentTokenIsRejected() {
    assertThat(
            ConfirmationTokenCodec.matches(
                ConfirmationTokenCodec.encode("abc", now.plusSeconds(60)), "other", now))
        .isFalse();
  }

  @Test
  void malformedValuesAreRejected() {
    assertThat(ConfirmationTokenCodec.matches("no-separator", "no-separator", now)).isFalse();
    assertThat(ConfirmationTokenCodec.matches("abc|not-a-number", "abc", now)).isFalse();
  }
}
