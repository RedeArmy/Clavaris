package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerificationTokenTest {

  private final AccountId accountId = new AccountId(UUID.randomUUID());

  @Test
  void issueCarriesTheGivenFieldsAndStartsActiveAndUnconsumed() {
    Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

    VerificationToken token =
        VerificationToken.issue(
            accountId, VerificationTokenType.EMAIL_VERIFICATION, "a-hash", expiresAt);

    assertThat(token.accountId()).isEqualTo(accountId);
    assertThat(token.type()).isEqualTo(VerificationTokenType.EMAIL_VERIFICATION);
    assertThat(token.tokenHash()).isEqualTo("a-hash");
    assertThat(token.expiresAt()).isEqualTo(expiresAt);
    assertThat(token.consumedAt()).isEmpty();
    assertThat(token.isActive()).isTrue();
  }

  @Test
  void consumeMarksTheTokenInactiveAndRecordsWhen() {
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.PASSWORD_RESET,
            "a-hash",
            Instant.now().plusSeconds(3600));

    token.consume();

    assertThat(token.consumedAt()).isPresent();
    assertThat(token.isActive()).isFalse();
  }

  @Test
  void aNaturallyExpiredUnconsumedTokenIsNotActive() {
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.EMAIL_VERIFICATION,
            "a-hash",
            Instant.now().minusSeconds(1));

    assertThat(token.consumedAt()).isEmpty();
    assertThat(token.isActive()).isFalse();
  }

  @Test
  void reconstituteRestoresEveryFieldExactly() {
    UUID id = UUID.randomUUID();
    Instant expiresAt = Instant.now().plusSeconds(3600);
    Instant consumedAt = Instant.now();

    VerificationToken token =
        VerificationToken.reconstitute(
            id, accountId, VerificationTokenType.PASSWORD_RESET, "a-hash", expiresAt, consumedAt);

    assertThat(token.id()).isEqualTo(id);
    assertThat(token.accountId()).isEqualTo(accountId);
    assertThat(token.type()).isEqualTo(VerificationTokenType.PASSWORD_RESET);
    assertThat(token.tokenHash()).isEqualTo("a-hash");
    assertThat(token.expiresAt()).isEqualTo(expiresAt);
    assertThat(token.consumedAt()).contains(consumedAt);
  }
}
