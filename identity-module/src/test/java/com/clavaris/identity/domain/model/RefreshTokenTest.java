package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

  private final AccountId accountId = new AccountId(UUID.randomUUID());
  private final UUID sessionId = UUID.randomUUID();

  @Test
  void issueCarriesTheGivenFieldsStartsActiveAndHasNoRotationParent() {
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);

    RefreshToken token = RefreshToken.issue(sessionId, accountId, "a-hash", expiresAt);

    assertThat(token.sessionId()).isEqualTo(sessionId);
    assertThat(token.accountId()).isEqualTo(accountId);
    assertThat(token.tokenHash()).isEqualTo("a-hash");
    assertThat(token.expiresAt()).isEqualTo(expiresAt);
    assertThat(token.rotatedFromId()).isEmpty();
    assertThat(token.isActive()).isTrue();
    assertThat(token.isRevoked()).isFalse();
  }

  @Test
  void rotatedFromLinksToTheSupersededTokenAndInheritsItsSessionAndAccount() {
    RefreshToken original =
        RefreshToken.issue(sessionId, accountId, "old-hash", Instant.now().plusSeconds(3600));

    RefreshToken rotated =
        RefreshToken.rotatedFrom(original, "new-hash", Instant.now().plusSeconds(3600));

    assertThat(rotated.rotatedFromId()).contains(original.id());
    assertThat(rotated.sessionId()).isEqualTo(sessionId);
    assertThat(rotated.accountId()).isEqualTo(accountId);
    assertThat(rotated.tokenHash()).isEqualTo("new-hash");
    assertThat(rotated.id()).isNotEqualTo(original.id());
  }

  @Test
  void rotatedFromDoesNotItselfRevokeTheSupersededToken() {
    // The caller (RotateRefreshTokenService) is responsible for calling revoke() on the old token
    // as its own explicit step — this factory only builds the new one.
    RefreshToken original =
        RefreshToken.issue(sessionId, accountId, "old-hash", Instant.now().plusSeconds(3600));

    RefreshToken.rotatedFrom(original, "new-hash", Instant.now().plusSeconds(3600));

    assertThat(original.isRevoked()).isFalse();
  }

  @Test
  void revokeMarksTheTokenInactiveAndRevoked() {
    RefreshToken token =
        RefreshToken.issue(sessionId, accountId, "a-hash", Instant.now().plusSeconds(3600));

    token.revoke();

    assertThat(token.isActive()).isFalse();
    assertThat(token.isRevoked()).isTrue();
    assertThat(token.revokedAt()).isPresent();
  }

  @Test
  void isActiveIsFalseOnceExpiredEvenWithoutExplicitRevocation() {
    RefreshToken token =
        RefreshToken.issue(sessionId, accountId, "a-hash", Instant.now().minusSeconds(1));

    assertThat(token.isActive()).isFalse();
    // The distinction BR-ID-03's reuse-detection actually relies on: natural expiry is not the
    // same signal as "this was already rotated away or explicitly revoked."
    assertThat(token.isRevoked()).isFalse();
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdAndRotationParentRatherThanMintingNewOnes() {
    UUID persistedId = UUID.randomUUID();
    UUID rotatedFromId = UUID.randomUUID();
    Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant expiresAt = Instant.parse("2026-02-01T00:00:00Z");

    RefreshToken token =
        RefreshToken.reconstitute(
            persistedId, sessionId, accountId, "a-hash", rotatedFromId, issuedAt, expiresAt, null);

    assertThat(token.id()).isEqualTo(persistedId);
    assertThat(token.rotatedFromId()).contains(rotatedFromId);
    assertThat(token.issuedAt()).isEqualTo(issuedAt);
    assertThat(token.expiresAt()).isEqualTo(expiresAt);
  }
}
