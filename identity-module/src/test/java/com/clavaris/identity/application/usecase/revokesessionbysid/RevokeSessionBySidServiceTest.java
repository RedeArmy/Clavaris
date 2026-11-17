package com.clavaris.identity.application.usecase.revokesessionbysid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Session;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TD-SEC-100: a logout that names a sid ends that Session's tokens, inside its Organization. */
class RevokeSessionBySidServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

  private final AccountId accountId = AccountId.newId();
  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final SecurityMetricsRecorder metrics = mock(SecurityMetricsRecorder.class);
  private final RevokeSessionBySidService service =
      new RevokeSessionBySidService(
          refreshTokens, sessions, accounts, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

  private Session aSession() {
    return Session.open(accountId, List.of("openid"), NOW.minusSeconds(60));
  }

  @Test
  void endsTheSessionRecordedUnderTheSid() {
    Session session = aSession();
    when(sessions.findByOidcSid("the-sid")).thenReturn(Optional.of(session));
    when(accounts.findOrganizationIdById(accountId)).thenReturn(Optional.of(organizationId));

    boolean revoked = service.handle(new RevokeSessionBySidCommand("the-sid", organizationId));

    assertThat(revoked).isTrue();
    verify(refreshTokens).revokeAllActiveForSession(session.id(), NOW);
    verify(sessions).save(session);
    assertThat(session.revokedAt()).contains(NOW);
    verify(metrics).increment("clavaris.auth.session.revoked_by_logout");
  }

  // A Session that predates recording a sid reports its own id as the sid of a refreshed token.
  @Test
  void fallsBackToTheSessionIdWhenNoSidWasRecorded() {
    Session session = aSession();
    String sid = session.id().toString();
    when(sessions.findByOidcSid(sid)).thenReturn(Optional.empty());
    when(sessions.findById(session.id())).thenReturn(Optional.of(session));
    when(accounts.findOrganizationIdById(accountId)).thenReturn(Optional.of(organizationId));

    assertThat(service.handle(new RevokeSessionBySidCommand(sid, organizationId))).isTrue();
    verify(refreshTokens).revokeAllActiveForSession(session.id(), NOW);
  }

  @Test
  void aSidThatIsNeitherRecordedNorASessionIdIsUnknown() {
    when(sessions.findByOidcSid("garbage")).thenReturn(Optional.empty());

    assertThat(service.handle(new RevokeSessionBySidCommand("garbage", organizationId))).isFalse();
    verify(refreshTokens, never()).revokeAllActiveForSession(any(), any());
  }

  @Test
  void aSessionOfAnotherOrganizationIsUnknown() {
    Session session = aSession();
    when(sessions.findByOidcSid("the-sid")).thenReturn(Optional.of(session));
    when(accounts.findOrganizationIdById(accountId))
        .thenReturn(Optional.of(new OrganizationId(UUID.randomUUID())));

    assertThat(service.handle(new RevokeSessionBySidCommand("the-sid", organizationId))).isFalse();
    verify(refreshTokens, never()).revokeAllActiveForSession(any(), any());
    verify(sessions, never()).save(any());
  }
}
