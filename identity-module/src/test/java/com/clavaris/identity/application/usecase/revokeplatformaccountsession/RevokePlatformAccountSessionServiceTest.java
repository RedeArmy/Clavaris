package com.clavaris.identity.application.usecase.revokeplatformaccountsession;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.ActivePlatformAccountSession;
import com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount.PlatformAccountActiveSessionsRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** TD-FUT-026: platform-tier mirror of {@code RevokeAccountSessionServiceTest}. */
class RevokePlatformAccountSessionServiceTest {

  private final PlatformAccountActiveSessionsRepository activeSessions =
      mock(PlatformAccountActiveSessionsRepository.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final RevokePlatformAccountSessionService service =
      new RevokePlatformAccountSessionService(activeSessions, auditEvents);

  @Test
  void revokesASessionThePlatformAccountActuallyOwnsAndAuditsIt() {
    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    ActivePlatformAccountSession session =
        new ActivePlatformAccountSession(
            "real-session", "UA", "1.2.3.4", Instant.now(), Instant.now());
    when(activeSessions.findByPlatformAccountIdAndSessionId(platformAccountId, "real-session"))
        .thenReturn(Optional.of(session));

    service.handle(new RevokePlatformAccountSessionCommand(platformAccountId, "real-session"));

    verify(activeSessions).revoke("real-session");
    verify(auditEvents)
        .write(
            AuditActor.platformAccount(platformAccountId.value()),
            "platform_account.session_revoked",
            "Session",
            "real-session",
            null);
  }

  @Test
  void rejectsASessionThatDoesNotResolveForThisPlatformAccountWithoutRevokingAnything() {
    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    when(activeSessions.findByPlatformAccountIdAndSessionId(
            platformAccountId, "someone-elses-session"))
        .thenReturn(Optional.empty());
    RevokePlatformAccountSessionCommand command =
        new RevokePlatformAccountSessionCommand(platformAccountId, "someone-elses-session");

    assertThatExceptionOfType(PlatformAccountSessionNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(activeSessions, never()).revoke(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void anAuditWriteFailureNeverPropagatesSinceTheRevokeIsAlreadyDurable() {
    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    ActivePlatformAccountSession session =
        new ActivePlatformAccountSession(
            "real-session", "UA", "1.2.3.4", Instant.now(), Instant.now());
    when(activeSessions.findByPlatformAccountIdAndSessionId(platformAccountId, "real-session"))
        .thenReturn(Optional.of(session));
    doThrow(new RuntimeException("Postgres hiccup"))
        .when(auditEvents)
        .write(any(), any(), any(), any(), any());
    RevokePlatformAccountSessionCommand command =
        new RevokePlatformAccountSessionCommand(platformAccountId, "real-session");

    assertThatCode(() -> service.handle(command)).doesNotThrowAnyException();

    verify(activeSessions).revoke("real-session");
  }
}
