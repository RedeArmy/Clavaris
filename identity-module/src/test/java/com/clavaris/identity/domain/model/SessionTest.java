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
            persistedId,
            accountId,
            List.of("openid"),
            createdAt,
            createdAt,
            lastSeenAt,
            null,
            null);

    assertThat(session.id()).isEqualTo(persistedId);
    assertThat(session.authenticatedAt()).isEqualTo(createdAt);
    assertThat(session.createdAt()).isEqualTo(createdAt);
    assertThat(session.lastSeenAt()).isEqualTo(lastSeenAt);
    assertThat(session.revokedAt()).isEmpty();
  }

  // OIDC Core §2/§12.2: the authentication instant is kept as given (earlier than the Session's
  // own opening, since the code is exchanged after the login), so a refreshed ID token can repeat
  // the first one's auth_time.
  @Test
  void openKeepsTheAuthenticationInstantItIsGiven() {
    Instant login = Instant.now().minusSeconds(30);

    Session session = Session.open(accountId, List.of("openid"), login);

    assertThat(session.authenticatedAt()).isEqualTo(login);
    assertThat(session.createdAt()).isAfter(login);
  }

  @Test
  void openFallsBackToItsOwnOpeningTimeWithoutAnAuthenticationInstant() {
    Session session = Session.open(accountId, List.of("openid"));

    assertThat(session.authenticatedAt()).isEqualTo(session.createdAt());
  }

  @Test
  void openNeverTrustsAnAuthenticationInstantFromTheFuture() {
    Session session = Session.open(accountId, List.of("openid"), Instant.now().plusSeconds(3600));

    assertThat(session.authenticatedAt()).isEqualTo(session.createdAt());
  }

  // OIDC Core 12.2: the sid of the first ID token is kept once and never replaced, so every
  // refreshed ID token repeats the same one.
  @Test
  void theOidcSidIsRecordedOnceAndNeverReplacedOrBlank() {
    Session session = Session.open(accountId, List.of("openid"));
    assertThat(session.oidcSid()).isEmpty();

    session.recordOidcSid("  ");
    session.recordOidcSid(null);
    assertThat(session.oidcSid()).isEmpty();

    session.recordOidcSid("first-sid");
    session.recordOidcSid("second-sid");
    assertThat(session.oidcSid()).contains("first-sid");
  }
}
