package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;
import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

class AccountActiveSessionsRepositoryBridgeTest {

  private final FindByIndexNameSessionRepository<Session> sessionRepository =
      mock(FindByIndexNameSessionRepository.class);
  private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
  private final AccountActiveSessionsRepositoryBridge bridge =
      new AccountActiveSessionsRepositoryBridge(sessionRepository, sessionRegistry);

  @Test
  void findAllByAccountIdMapsEveryLiveSessionsAttributesAndTimestamps() {
    AccountId accountId = AccountId.newId();
    Session session = mock(Session.class);
    when(session.getId()).thenReturn("session-1");
    when(session.getAttribute(SessionDeviceAttributes.USER_AGENT)).thenReturn("Mozilla/5.0");
    when(session.getAttribute(SessionDeviceAttributes.SOURCE_IP)).thenReturn("1.2.3.4");
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant lastAccessedAt = Instant.parse("2026-01-02T00:00:00Z");
    when(session.getCreationTime()).thenReturn(createdAt);
    when(session.getLastAccessedTime()).thenReturn(lastAccessedAt);
    when(sessionRepository.findByIndexNameAndIndexValue(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
            accountId.value().toString()))
        .thenReturn(Map.of("session-1", session));

    var result = bridge.findAllByAccountId(accountId);

    assertThat(result)
        .containsExactly(
            new ActiveAccountSession(
                "session-1", "Mozilla/5.0", "1.2.3.4", createdAt, lastAccessedAt));
  }

  @Test
  void findByAccountIdAndSessionIdFiltersFromTheSameOneCallRead() {
    AccountId accountId = AccountId.newId();
    Session session = mock(Session.class);
    when(session.getId()).thenReturn("the-one-session");
    when(sessionRepository.findByIndexNameAndIndexValue(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
            accountId.value().toString()))
        .thenReturn(Map.of("the-one-session", session));

    assertThat(bridge.findByAccountIdAndSessionId(accountId, "the-one-session")).isPresent();
    assertThat(bridge.findByAccountIdAndSessionId(accountId, "someone-elses-session")).isEmpty();
  }

  @Test
  void revokeExpiresANormallyStillLiveSession() {
    SessionInformation info = mock(SessionInformation.class);
    when(sessionRegistry.getSessionInformation("session-1")).thenReturn(info);

    bridge.revoke("session-1");

    verify(info).expireNow();
  }

  @Test
  void revokeIsANoOpForASessionThatIsAlreadyGone() {
    when(sessionRegistry.getSessionInformation("already-gone")).thenReturn(null);

    // Must not throw — same "already gone is a benign race, not an error" contract this port's
    // own Javadoc documents. An explicit assertion, not just "the test method returned" (Sonar
    // java:S2699) — this is the one real outcome worth proving for a genuinely no-op path.
    assertThatCode(() -> bridge.revoke("already-gone")).doesNotThrowAnyException();
  }
}
