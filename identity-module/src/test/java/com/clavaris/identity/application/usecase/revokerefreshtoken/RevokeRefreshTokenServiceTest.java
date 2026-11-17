package com.clavaris.identity.application.usecase.revokerefreshtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.RefreshToken;
import com.clavaris.identity.domain.model.Session;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TD-SEC-100: revoking a refresh token ends its Session, inside its own Organization. */
class RevokeRefreshTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

  private final AccountId accountId = AccountId.newId();
  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final SecurityMetricsRecorder metrics = mock(SecurityMetricsRecorder.class);
  private final RevokeRefreshTokenService service =
      new RevokeRefreshTokenService(
          refreshTokens, sessions, accounts, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

  private RefreshToken tokenOf(final Session session, final String raw) {
    return RefreshToken.issue(
        session.id(),
        accountId,
        RefreshTokenSecret.hash(raw),
        NOW.minusSeconds(60),
        NOW.plusSeconds(3600));
  }

  @Test
  void endsTheSessionOfARefreshTokenOfThisOrganization() {
    Session session = Session.open(accountId, List.of("openid"), NOW.minusSeconds(60));
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash("raw-token")))
        .thenReturn(Optional.of(tokenOf(session, "raw-token")));
    when(accounts.findOrganizationIdById(accountId)).thenReturn(Optional.of(organizationId));
    when(sessions.findById(session.id())).thenReturn(Optional.of(session));

    boolean revoked = service.handle(new RevokeRefreshTokenCommand("raw-token", organizationId));

    assertThat(revoked).isTrue();
    verify(refreshTokens).revokeAllActiveForSession(session.id(), NOW);
    verify(sessions).save(session);
    assertThat(session.isActive()).isFalse();
    assertThat(session.revokedAt()).contains(NOW);
    verify(metrics).increment("clavaris.auth.refresh_token.revoked");
  }

  @Test
  void anUnknownTokenIsFalseAndTouchesNothing() {
    when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.empty());

    boolean revoked = service.handle(new RevokeRefreshTokenCommand("nope", organizationId));

    assertThat(revoked).isFalse();
    verifyNoInteractions(sessions, metrics);
    verify(refreshTokens, never()).revokeAllActiveForSession(any(), any());
  }

  @Test
  void aTokenOfAnotherOrganizationIsTreatedAsUnknown() {
    Session session = Session.open(accountId, List.of("openid"), NOW.minusSeconds(60));
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash("raw-token")))
        .thenReturn(Optional.of(tokenOf(session, "raw-token")));
    when(accounts.findOrganizationIdById(accountId))
        .thenReturn(Optional.of(new OrganizationId(UUID.randomUUID())));

    boolean revoked = service.handle(new RevokeRefreshTokenCommand("raw-token", organizationId));

    assertThat(revoked).isFalse();
    verify(refreshTokens, never()).revokeAllActiveForSession(any(), any());
    verifyNoInteractions(sessions, metrics);
  }

  @Test
  void aTokenWhoseAccountIsGoneIsTreatedAsUnknown() {
    Session session = Session.open(accountId, List.of("openid"), NOW.minusSeconds(60));
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash("raw-token")))
        .thenReturn(Optional.of(tokenOf(session, "raw-token")));
    when(accounts.findOrganizationIdById(accountId)).thenReturn(Optional.empty());

    assertThat(service.handle(new RevokeRefreshTokenCommand("raw-token", organizationId)))
        .isFalse();
    verify(refreshTokens, never()).revokeAllActiveForSession(any(), any());
  }

  // A rotated-away token still names its chain: presenting it asks to stop the chain, and the
  // successor the client never saw is what has to die.
  @Test
  void anAlreadyRevokedTokenStillEndsTheWholeSession() {
    Session session = Session.open(accountId, List.of("openid"), NOW.minusSeconds(60));
    RefreshToken old = tokenOf(session, "rotated-away");
    old.revoke(NOW.minusSeconds(30));
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash("rotated-away")))
        .thenReturn(Optional.of(old));
    when(accounts.findOrganizationIdById(accountId)).thenReturn(Optional.of(organizationId));
    when(sessions.findById(session.id())).thenReturn(Optional.of(session));

    assertThat(service.handle(new RevokeRefreshTokenCommand("rotated-away", organizationId)))
        .isTrue();
    verify(refreshTokens).revokeAllActiveForSession(session.id(), NOW);
  }

  @Test
  void aMissingSessionRowStillRevokesItsTokens() {
    Session session = Session.open(accountId, List.of("openid"), NOW.minusSeconds(60));
    when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash("raw-token")))
        .thenReturn(Optional.of(tokenOf(session, "raw-token")));
    when(accounts.findOrganizationIdById(accountId)).thenReturn(Optional.of(organizationId));
    when(sessions.findById(session.id())).thenReturn(Optional.empty());

    assertThat(service.handle(new RevokeRefreshTokenCommand("raw-token", organizationId))).isTrue();
    verify(refreshTokens).revokeAllActiveForSession(session.id(), NOW);
    verify(sessions, never()).save(any());
  }
}
