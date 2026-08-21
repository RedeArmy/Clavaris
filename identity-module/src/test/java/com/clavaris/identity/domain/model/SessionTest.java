package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionTest {

  private final AccountId accountId = new AccountId(UUID.randomUUID());

  @Test
  void openCarriesTheGivenFieldsAndStartsActive() {
    Session session = Session.open(accountId, List.of("openid", "profile"));

    assertThat(session.accountId()).isEqualTo(accountId);
    assertThat(session.scopes()).containsExactly("openid", "profile");
    assertThat(session.createdAt()).isNotNull();
    assertThat(session.lastSeenAt()).isEqualTo(session.createdAt());
    assertThat(session.isActive()).isTrue();
    assertThat(session.revokedAt()).isEmpty();
  }

  @Test
  void touchAdvancesLastSeenAtWithoutAffectingRevocationState() {
    Session session = Session.open(accountId, List.of("openid"));
    Instant openedLastSeenAt = session.lastSeenAt();

    session.touch();

    assertThat(session.lastSeenAt()).isAfterOrEqualTo(openedLastSeenAt);
    assertThat(session.isActive()).isTrue();
  }

  @Test
  void revokeMarksTheSessionInactiveWithoutErasingItsMetadata() {
    Session session = Session.open(accountId, List.of("openid"));

    session.revoke();

    assertThat(session.isActive()).isFalse();
    assertThat(session.revokedAt()).isPresent();
    assertThat(session.accountId()).isEqualTo(accountId);
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    UUID persistedId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant lastSeenAt = Instant.parse("2026-01-02T00:00:00Z");

    Session session =
        Session.reconstitute(
            persistedId, accountId, List.of("openid"), createdAt, lastSeenAt, null);

    assertThat(session.id()).isEqualTo(persistedId);
    assertThat(session.createdAt()).isEqualTo(createdAt);
    assertThat(session.lastSeenAt()).isEqualTo(lastSeenAt);
    assertThat(session.revokedAt()).isEmpty();
  }
}
