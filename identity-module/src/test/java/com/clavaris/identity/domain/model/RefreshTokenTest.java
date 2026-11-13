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

    RefreshToken token =
        RefreshToken.issue(sessionId, accountId, "a-hash", Instant.now(), expiresAt);

    assertThat(token.sessionId()).isEqualTo(sessionId);
    assertThat(token.accountId()).isEqualTo(accountId);
    assertThat(token.tokenHash()).isEqualTo("a-hash");
    assertThat(token.expiresAt()).isEqualTo(expiresAt);
    assertThat(token.rotatedFromId()).isEmpty();
    assertThat(token.isActive(Instant.now())).isTrue();
    assertThat(token.isRevoked()).isFalse();
  }

  @Test
  void rotatedFromLinksToTheSupersededTokenAndInheritsItsSessionAndAccount() {
    RefreshToken original =
        RefreshToken.issue(
            sessionId, accountId, "old-hash", Instant.now(), Instant.now().plusSeconds(3600));

    RefreshToken rotated =
        RefreshToken.rotatedFrom(
            original, "new-hash", Instant.now(), Instant.now().plusSeconds(3600));

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
        RefreshToken.issue(
            sessionId, accountId, "old-hash", Instant.now(), Instant.now().plusSeconds(3600));

    RefreshToken.rotatedFrom(original, "new-hash", Instant.now(), Instant.now().plusSeconds(3600));

    assertThat(original.isRevoked()).isFalse();
  }

  @Test
  void revokeMarksTheTokenInactiveAndRevoked() {
    RefreshToken token =
        RefreshToken.issue(
            sessionId, accountId, "a-hash", Instant.now(), Instant.now().plusSeconds(3600));

    token.revoke(Instant.now());

    assertThat(token.isActive(Instant.now())).isFalse();
    assertThat(token.isRevoked()).isTrue();
    assertThat(token.revokedAt()).isPresent();
  }

  @Test
  void isActiveIsFalseOnceExpiredEvenWithoutExplicitRevocation() {
    RefreshToken token =
        RefreshToken.issue(
            sessionId, accountId, "a-hash", Instant.now(), Instant.now().minusSeconds(1));

    assertThat(token.isActive(Instant.now())).isFalse();
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

  // TD-ARCH-052: the time is a parameter now, so the boundary is exact and nothing waits for it.
  @Test
  void aTokenIsActiveUpToButNotAtItsExpiryInstant() {
    Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant expiresAt = Instant.parse("2026-01-31T00:00:00Z");
    RefreshToken token = RefreshToken.issue(sessionId, accountId, "a-hash", issuedAt, expiresAt);

    assertThat(token.isActive(expiresAt.minusNanos(1))).isTrue();
    assertThat(token.isActive(expiresAt)).isFalse();
    assertThat(token.isActive(expiresAt.plusSeconds(1))).isFalse();
    assertThat(token.issuedAt()).isEqualTo(issuedAt);
  }

  @Test
  void revokingStoresTheGivenInstantAndEndsTheTokenWhateverItsExpiry() {
    Instant at = Instant.parse("2026-01-02T03:04:05Z");
    RefreshToken token =
        RefreshToken.issue(
            sessionId,
            accountId,
            "a-hash",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-12-31T00:00:00Z"));

    token.revoke(at);

    assertThat(token.revokedAt()).contains(at);
    assertThat(token.isRevoked()).isTrue();
    assertThat(token.isActive(at)).isFalse();
  }

  @Test
  void aRotatedTokenCarriesTheGivenIssueInstant() {
    RefreshToken original =
        RefreshToken.issue(
            sessionId,
            accountId,
            "old",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-02-01T00:00:00Z"));
    Instant at = Instant.parse("2026-01-10T00:00:00Z");

    RefreshToken rotated =
        RefreshToken.rotatedFrom(original, "new", at, Instant.parse("2026-03-01T00:00:00Z"));

    assertThat(rotated.issuedAt()).isEqualTo(at);
  }
}
