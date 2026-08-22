package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformVerificationTokenTest {

  private final PlatformAccountId platformAccountId = PlatformAccountId.newId();

  @Test
  void issueCarriesTheGivenFieldsAndStartsActiveAndUnconsumed() {
    Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            platformAccountId, VerificationTokenType.EMAIL_VERIFICATION, "a-hash", expiresAt);

    assertThat(token.platformAccountId()).isEqualTo(platformAccountId);
    assertThat(token.type()).isEqualTo(VerificationTokenType.EMAIL_VERIFICATION);
    assertThat(token.tokenHash()).isEqualTo("a-hash");
    assertThat(token.consumedAt()).isEmpty();
    assertThat(token.isActive()).isTrue();
  }

  @Test
  void consumeMarksTheTokenInactiveAndRecordsWhen() {
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            platformAccountId,
            VerificationTokenType.PASSWORD_RESET,
            "a-hash",
            Instant.now().plusSeconds(3600));

    token.consume();

    assertThat(token.consumedAt()).isPresent();
    assertThat(token.isActive()).isFalse();
  }

  @Test
  void aNaturallyExpiredUnconsumedTokenIsNotActive() {
    PlatformVerificationToken token =
        PlatformVerificationToken.issue(
            platformAccountId,
            VerificationTokenType.EMAIL_VERIFICATION,
            "a-hash",
            Instant.now().minusSeconds(1));

    assertThat(token.isActive()).isFalse();
  }

  @Test
  void reconstituteRestoresEveryFieldExactly() {
    UUID id = UUID.randomUUID();
    Instant expiresAt = Instant.now().plusSeconds(3600);
    Instant consumedAt = Instant.now();

    PlatformVerificationToken token =
        PlatformVerificationToken.reconstitute(
            id,
            platformAccountId,
            VerificationTokenType.PASSWORD_RESET,
            "a-hash",
            expiresAt,
            consumedAt);

    assertThat(token.id()).isEqualTo(id);
    assertThat(token.platformAccountId()).isEqualTo(platformAccountId);
    assertThat(token.type()).isEqualTo(VerificationTokenType.PASSWORD_RESET);
    assertThat(token.expiresAt()).isEqualTo(expiresAt);
    assertThat(token.consumedAt()).contains(consumedAt);
  }
}
