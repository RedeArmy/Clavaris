package com.clavaris.identity.application.usecase.forcepasswordresetforaccount;

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

class ForcePasswordResetForAccountServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private AccountRepository accounts;
  private AuditEventRecorder auditEvents;
  private ForcePasswordResetForAccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new ForcePasswordResetForAccountService(accounts, auditEvents);
  }

  private Account registeredAccount() {
    Account account =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("force-me@example.com"));
    account.attachPasswordCredential("argon2id$hashed");
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    return account;
  }

  @Test
  void marksTheAccountAndPersistsIt() {
    Account account = registeredAccount();

    service.handle(new ForcePasswordResetForAccountCommand(account.id(), ACTOR));

    assertThat(account.passwordResetRequiredAt()).isPresent();
    verify(accounts).save(account);
  }

  @Test
  void recordsAnAuditEvent() {
    Account account = registeredAccount();

    service.handle(new ForcePasswordResetForAccountCommand(account.id(), ACTOR));

    verify(auditEvents)
        .write(
            ACTOR,
            "account.password_reset_required",
            "Account",
            account.id().value().toString(),
            null);
  }

  @Test
  void rejectsAnUnknownAccountWithoutRecordingAnything() {
    AccountId unknownAccountId = AccountId.newId();
    when(accounts.findById(unknownAccountId)).thenReturn(Optional.empty());
    ForcePasswordResetForAccountCommand command =
        new ForcePasswordResetForAccountCommand(unknownAccountId, ACTOR);

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}
