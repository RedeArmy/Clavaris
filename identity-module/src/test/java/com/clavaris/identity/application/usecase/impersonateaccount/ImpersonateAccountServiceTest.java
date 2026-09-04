package com.clavaris.identity.application.usecase.impersonateaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImpersonateAccountServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private AccountRepository accounts;
  private AuditEventRecorder auditEvents;
  private ImpersonateAccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new ImpersonateAccountService(accounts, auditEvents);
  }

  private Account activeAccount() {
    Account account =
        Account.register(
            new OrganizationId(UUID.randomUUID()), new Email("impersonate-me@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    return account;
  }

  @Test
  void returnsTheAccountAndOrganizationIdForAnActiveAccount() {
    Account account = activeAccount();

    ImpersonateAccountResult result =
        service.handle(new ImpersonateAccountCommand(account.id(), ACTOR));

    assertThat(result.accountId()).isEqualTo(account.id());
    assertThat(result.organizationId()).isEqualTo(account.organizationId());
  }

  @Test
  void recordsAnAuditEvent() {
    Account account = activeAccount();

    service.handle(new ImpersonateAccountCommand(account.id(), ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "account.impersonation_started",
            "Account",
            account.id().value().toString(),
            null);
  }

  @Test
  void rejectsAnUnknownAccountWithoutRecordingAnything() {
    AccountId unknownAccountId = AccountId.newId();
    when(accounts.findById(unknownAccountId)).thenReturn(Optional.empty());

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(new ImpersonateAccountCommand(unknownAccountId, ACTOR)));

    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsASuspendedAccountWithoutRecordingAnything() {
    Account account = activeAccount();
    account.suspend();

    assertThatExceptionOfType(AccountNotActiveException.class)
        .isThrownBy(() -> service.handle(new ImpersonateAccountCommand(account.id(), ACTOR)));

    verify(auditEvents, never()).write(any(), any(), any(), any(), any());
  }
}
