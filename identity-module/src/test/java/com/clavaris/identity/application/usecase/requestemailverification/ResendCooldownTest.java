package com.clavaris.identity.application.usecase.requestemailverification;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResendCooldownTest {

  private static final Duration TTL = Duration.ofMinutes(10);
  private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

  // A token expires at issue time + TTL, so one issued `age` ago expires at NOW + TTL - age.
  private static Optional<VerificationToken> issuedAgo(final Duration age) {
    return Optional.of(
        VerificationToken.issue(
            AccountId.newId(),
            VerificationTokenType.EMAIL_SIGN_IN_CODE,
            "hash",
            NOW.plus(TTL).minus(age)));
  }

  @Test
  void aSecretIssuedTenSecondsAgoIsStillInTheWait() {
    assertThat(ResendCooldown.isActive(issuedAgo(Duration.ofSeconds(10)), TTL, NOW)).isTrue();
  }

  @Test
  void aSecretIssuedJustUnderTheWaitAgoIsStillInIt() {
    assertThat(ResendCooldown.isActive(issuedAgo(Duration.ofSeconds(29)), TTL, NOW)).isTrue();
  }

  @Test
  void aSecretIssuedExactlyTheWaitAgoIsNoLongerInIt() {
    assertThat(ResendCooldown.isActive(issuedAgo(ResendCooldown.WAIT), TTL, NOW)).isFalse();
  }

  @Test
  void aSecretIssuedAMinuteAgoIsNotInTheWait() {
    assertThat(ResendCooldown.isActive(issuedAgo(Duration.ofMinutes(1)), TTL, NOW)).isFalse();
  }

  @Test
  void thereIsNoWaitWhenThereIsNoActiveSecret() {
    assertThat(ResendCooldown.isActive(Optional.empty(), TTL, NOW)).isFalse();
  }

  @Test
  void theTtlOfTheTypeIsWhatDatesTheSecret() {
    // The same absolute expiry means a different issue time under a different TTL.
    Optional<VerificationToken> expiresInTwentyNineMinutes =
        Optional.of(
            VerificationToken.issue(
                AccountId.newId(),
                VerificationTokenType.PASSWORD_RESET,
                "hash",
                NOW.plus(Duration.ofMinutes(29))));

    assertThat(ResendCooldown.isActive(expiresInTwentyNineMinutes, Duration.ofMinutes(30), NOW))
        .as("issued a minute ago under a 30 minute TTL")
        .isFalse();
    assertThat(ResendCooldown.isActive(expiresInTwentyNineMinutes, Duration.ofMinutes(29), NOW))
        .as("issued just now under a 29 minute TTL")
        .isTrue();
  }
}
