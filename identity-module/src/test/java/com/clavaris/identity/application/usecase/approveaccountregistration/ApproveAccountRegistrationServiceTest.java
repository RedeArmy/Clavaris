package com.clavaris.identity.application.usecase.approveaccountregistration;

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

class ApproveAccountRegistrationServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private AccountRepository accounts;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private ApproveAccountRegistrationService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service = new ApproveAccountRegistrationService(accounts, auditEvents, outbox);
  }

  private Account pendingApprovalAccount() {
    Account account =
        Account.registerPendingApproval(
            new OrganizationId(UUID.randomUUID()), new Email("pending@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    return account;
  }

  @Test
  void activatesTheAccountAndPersistsIt() {
    Account account = pendingApprovalAccount();

    service.handle(new ApproveAccountRegistrationCommand(account.id(), ACTOR));

    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    verify(accounts).save(account);
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEvent() {
    Account account = pendingApprovalAccount();

    service.handle(new ApproveAccountRegistrationCommand(account.id(), ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "account.registration_approved",
            "Account",
            account.id().value().toString(),
            null);
    verify(outbox)
        .write(
            eq("account.registration_approved"),
            eq(account.id()),
            eq(account.organizationId()),
            any());
  }

  @Test
  void rejectsAnUnknownAccountWithoutRecordingAnything() {
    AccountId unknownAccountId = AccountId.newId();
    when(accounts.findById(unknownAccountId)).thenReturn(Optional.empty());
    ApproveAccountRegistrationCommand command =
        new ApproveAccountRegistrationCommand(unknownAccountId, ACTOR);

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }
}
