package com.clavaris.identity.application.usecase.updateaccountprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
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

class UpdateAccountProfileServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private AccountRepository accounts;
  private AuditEventRecorder auditEvents;
  private UpdateAccountProfileService service;
  private Account account;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new UpdateAccountProfileService(accounts, auditEvents);
    account = Account.register(new OrganizationId(UUID.randomUUID()), new Email("ada@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
  }

  @Test
  void updatesTheNameSavesAndAudits() {
    service.handle(new UpdateAccountProfileCommand(account.id(), "Ada", "Lovelace", ACTOR));

    assertThat(account.firstName()).contains("Ada");
    assertThat(account.lastName()).contains("Lovelace");
    verify(accounts).save(account);
    verify(auditEvents)
        .write(ACTOR, "account.profile_updated", "Account", account.id().value().toString(), null);
  }

  @Test
  void throwsWhenTheAccountDoesNotExist() {
    AccountId missing = AccountId.newId();
    when(accounts.findById(missing)).thenReturn(Optional.empty());
    UpdateAccountProfileCommand command =
        new UpdateAccountProfileCommand(missing, "Ada", "Lovelace", ACTOR);

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(auditEvents);
  }
}
