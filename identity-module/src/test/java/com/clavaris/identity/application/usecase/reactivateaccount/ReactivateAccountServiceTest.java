package com.clavaris.identity.application.usecase.reactivateaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReactivateAccountServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private AccountRepository accounts;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private ReactivateAccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service = new ReactivateAccountService(accounts, auditEvents, outbox);
  }

  private Account suspendedAccount() {
    Account account =
        Account.register(
            new OrganizationId(UUID.randomUUID()), new Email("reactivate-me@example.com"));
    account.attachPasswordCredential("argon2id$hashed");
    account.suspend();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    return account;
  }

  @Test
  void reactivatesTheAccountAndPersistsTheNewStatus() {
    Account account = suspendedAccount();

    service.handle(new ReactivateAccountCommand(account.id(), ACTOR));

    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    verify(accounts).save(account);
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEvent() {
    Account account = suspendedAccount();

    service.handle(new ReactivateAccountCommand(account.id(), ACTOR));

    verify(auditEvents)
        .write(ACTOR, "account.reactivated", "Account", account.id().value().toString(), null);
    verify(outbox).write(eq("account.reactivated"), eq(account.id()), any());
  }

  @Test
  void rejectsAnUnknownAccountWithoutRecordingAnything() {
    AccountId unknownAccountId = AccountId.newId();
    when(accounts.findById(unknownAccountId)).thenReturn(Optional.empty());
    ReactivateAccountCommand command = new ReactivateAccountCommand(unknownAccountId, ACTOR);

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }
}
