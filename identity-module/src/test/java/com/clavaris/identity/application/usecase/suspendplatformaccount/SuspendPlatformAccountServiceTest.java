package com.clavaris.identity.application.usecase.suspendplatformaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.PlatformAccountSessionRevoker;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SuspendPlatformAccountServiceTest {

  private PlatformAccountRepository accounts;
  private PlatformAccountSessionRevoker sessionRevoker;
  private AuditEventRecorder auditEvents;
  private SuspendPlatformAccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    sessionRevoker = mock(PlatformAccountSessionRevoker.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new SuspendPlatformAccountService(accounts, sessionRevoker, auditEvents);
  }

  @Test
  void suspendsTheAccountRevokesEverySessionAndAudits() {
    PlatformAccount account = PlatformAccount.register(new Email("operator@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    AuditActor actor = AuditActor.platformAccount(account.id().value());

    service.handle(new SuspendPlatformAccountCommand(account.id(), actor));

    assertThat(account.status()).isEqualTo(AccountStatus.SUSPENDED);
    verify(accounts).save(account);
    verify(sessionRevoker).revokeAllSessionsFor(account.id());
    verify(auditEvents)
        .write(
            eq(actor),
            eq("platform_account.suspended"),
            eq("PlatformAccount"),
            eq(account.id().value().toString()),
            isNull());
  }

  @Test
  void aMissingAccountThrowsAndRevokesNothing() {
    PlatformAccountId unknownId = PlatformAccountId.newId();
    when(accounts.findById(unknownId)).thenReturn(Optional.empty());
    SuspendPlatformAccountCommand command =
        new SuspendPlatformAccountCommand(unknownId, AuditActor.platformAccount(unknownId.value()));

    assertThatExceptionOfType(PlatformAccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(sessionRevoker, never()).revokeAllSessionsFor(unknownId);
  }

  @Test
  void reSuspendingAnAlreadySuspendedAccountIsIdempotent() {
    PlatformAccount account = PlatformAccount.register(new Email("operator@example.com"));
    account.suspend();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    AuditActor actor = AuditActor.platformAccount(account.id().value());

    service.handle(new SuspendPlatformAccountCommand(account.id(), actor));

    assertThat(account.status()).isEqualTo(AccountStatus.SUSPENDED);
    verify(sessionRevoker).revokeAllSessionsFor(account.id());
  }
}
