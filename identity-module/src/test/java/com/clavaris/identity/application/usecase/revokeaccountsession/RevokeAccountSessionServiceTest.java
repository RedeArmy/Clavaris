package com.clavaris.identity.application.usecase.revokeaccountsession;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.event.AccountSessionRevokedEvent;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RevokeAccountSessionServiceTest {

  private final AccountActiveSessionsRepository activeSessions =
      mock(AccountActiveSessionsRepository.class);
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final AuditEventRecorder auditEvents = mock(AuditEventRecorder.class);
  private final EventOutboxWriter outbox = mock(EventOutboxWriter.class);
  private final RevokeAccountSessionService service =
      new RevokeAccountSessionService(activeSessions, accounts, auditEvents, outbox);

  @Test
  void revokesASessionTheAccountActuallyOwnsAndAuditsAndPublishesIt() {
    AccountId accountId = AccountId.newId();
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    when(accounts.findOrganizationIdById(accountId)).thenReturn(Optional.of(organizationId));
    ActiveAccountSession session =
        new ActiveAccountSession("real-session", "UA", "1.2.3.4", Instant.now(), Instant.now());
    when(activeSessions.findByAccountIdAndSessionId(accountId, "real-session"))
        .thenReturn(Optional.of(session));

    service.handle(new RevokeAccountSessionCommand(accountId, "real-session"));

    verify(activeSessions).revoke("real-session");
    verify(auditEvents)
        .write(
            AuditActor.account(accountId.value()),
            "account.session_revoked",
            "Session",
            "real-session",
            null);
    // any(), not an exact expected event, matching SuspendAccountServiceTest's own precedent —
    // AccountSessionRevokedEvent.of stamps its own Instant.now(), which will never exactly equal
    // the one the service's own call captured a moment earlier.
    verify(outbox)
        .write(
            eq("account.session_revoked"),
            eq(accountId),
            any(),
            any(AccountSessionRevokedEvent.class));
  }

  @Test
  void rejectsASessionThatDoesNotResolveForThisAccountWithoutRevokingAnything() {
    // Covers both "doesn't exist" and "belongs to a different Account" — this repository lookup
    // can't distinguish the two, by design (SessionNotFoundException's own Javadoc).
    AccountId accountId = AccountId.newId();
    when(activeSessions.findByAccountIdAndSessionId(accountId, "someone-elses-session"))
        .thenReturn(Optional.empty());
    RevokeAccountSessionCommand command =
        new RevokeAccountSessionCommand(accountId, "someone-elses-session");

    assertThatExceptionOfType(SessionNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(activeSessions, never()).revoke(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  // TD-SEC-034 follow-up: this real Account is guaranteed to exist under normal operation
  // (command.accountId() is always the caller's own live, authenticated principal) — this proves
  // the purely defensive fallback still leaves the revocation and its audit trail intact even in
  // the case that should never actually happen.
  @Test
  void anUnresolvableAccountStillRevokesAndAuditsButSkipsTheOutboxEvent() {
    AccountId accountId = AccountId.newId();
    when(accounts.findOrganizationIdById(accountId)).thenReturn(Optional.empty());
    ActiveAccountSession session =
        new ActiveAccountSession("real-session", "UA", "1.2.3.4", Instant.now(), Instant.now());
    when(activeSessions.findByAccountIdAndSessionId(accountId, "real-session"))
        .thenReturn(Optional.of(session));

    service.handle(new RevokeAccountSessionCommand(accountId, "real-session"));

    verify(activeSessions).revoke("real-session");
    verify(auditEvents)
        .write(
            AuditActor.account(accountId.value()),
            "account.session_revoked",
            "Session",
            "real-session",
            null);
    verifyNoInteractions(outbox);
  }

  // TD-SEC-036 (SDE-III review, self-caught same day): the very case the first version of this
  // follow-up got wrong — a transient outbox failure must never surface past this method, since
  // the revoke and its audit entry are already durably complete by the time this runs.
  @Test
  void anOutboxWriteFailureNeverPropagatesSinceTheRevokeAndAuditAreAlreadyDurable() {
    AccountId accountId = AccountId.newId();
    when(accounts.findOrganizationIdById(accountId))
        .thenReturn(Optional.of(new OrganizationId(UUID.randomUUID())));
    ActiveAccountSession session =
        new ActiveAccountSession("real-session", "UA", "1.2.3.4", Instant.now(), Instant.now());
    when(activeSessions.findByAccountIdAndSessionId(accountId, "real-session"))
        .thenReturn(Optional.of(session));
    doThrow(new RuntimeException("Postgres hiccup")).when(outbox).write(any(), any(), any(), any());
    RevokeAccountSessionCommand command =
        new RevokeAccountSessionCommand(accountId, "real-session");

    assertThatCode(() -> service.handle(command)).doesNotThrowAnyException();

    verify(activeSessions).revoke("real-session");
    verify(auditEvents)
        .write(
            AuditActor.account(accountId.value()),
            "account.session_revoked",
            "Session",
            "real-session",
            null);
  }

  // Code review (2026-09-01): the gap TD-SEC-036's own fix left one call too narrow — an audit
  // failure must never propagate either, since the revoke it describes already happened.
  @Test
  void anAuditWriteFailureNeverPropagatesAndTheOutboxIsStillAttempted() {
    AccountId accountId = AccountId.newId();
    when(accounts.findOrganizationIdById(accountId))
        .thenReturn(Optional.of(new OrganizationId(UUID.randomUUID())));
    ActiveAccountSession session =
        new ActiveAccountSession("real-session", "UA", "1.2.3.4", Instant.now(), Instant.now());
    when(activeSessions.findByAccountIdAndSessionId(accountId, "real-session"))
        .thenReturn(Optional.of(session));
    doThrow(new RuntimeException("Postgres hiccup"))
        .when(auditEvents)
        .write(any(), any(), any(), any(), any());
    RevokeAccountSessionCommand command =
        new RevokeAccountSessionCommand(accountId, "real-session");

    assertThatCode(() -> service.handle(command)).doesNotThrowAnyException();

    verify(activeSessions).revoke("real-session");
    verify(outbox)
        .write(
            eq("account.session_revoked"),
            eq(accountId),
            any(),
            any(AccountSessionRevokedEvent.class));
  }

  // Code review (2026-09-01): same gap, one call earlier still — the
  // accounts.findOrganizationIdById
  // lookup feeding the outbox event was also bare. A transient failure here must degrade the same
  // way an already-not-found account does (skip the outbox event), not propagate.
  @Test
  void anAccountLookupFailureNeverPropagatesAndSkipsTheOutboxEvent() {
    AccountId accountId = AccountId.newId();
    when(accounts.findOrganizationIdById(accountId))
        .thenThrow(new RuntimeException("Postgres hiccup"));
    ActiveAccountSession session =
        new ActiveAccountSession("real-session", "UA", "1.2.3.4", Instant.now(), Instant.now());
    when(activeSessions.findByAccountIdAndSessionId(accountId, "real-session"))
        .thenReturn(Optional.of(session));
    RevokeAccountSessionCommand command =
        new RevokeAccountSessionCommand(accountId, "real-session");

    assertThatCode(() -> service.handle(command)).doesNotThrowAnyException();

    verify(activeSessions).revoke("real-session");
    verify(auditEvents)
        .write(
            AuditActor.account(accountId.value()),
            "account.session_revoked",
            "Session",
            "real-session",
            null);
    verifyNoInteractions(outbox);
  }
}
