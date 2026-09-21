package com.clavaris.identity.application.usecase.updateaccountpermissions;

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

class UpdateAccountPermissionsServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private AccountRepository accounts;
  private AuditEventRecorder auditEvents;
  private UpdateAccountPermissionsService service;
  private Account account;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new UpdateAccountPermissionsService(accounts, auditEvents);
    account = Account.register(new OrganizationId(UUID.randomUUID()), new Email("ada@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
  }

  @Test
  void enablesBothFlagsSavesAndAudits() {
    service.handle(new UpdateAccountPermissionsCommand(account.id(), true, true, ACTOR));

    assertThat(account.canDeleteOwnAccount()).isTrue();
    assertThat(account.bypassesDeviceTrust()).isTrue();
    verify(accounts).save(account);
    verify(auditEvents)
        .write(
            ACTOR, "account.permissions_updated", "Account", account.id().value().toString(), null);
  }

  @Test
  void disablesBothFlagsWhenSubmittedFalse() {
    account.allowSelfDelete();
    account.enableDeviceTrustBypass();

    service.handle(new UpdateAccountPermissionsCommand(account.id(), false, false, ACTOR));

    assertThat(account.canDeleteOwnAccount()).isFalse();
    assertThat(account.bypassesDeviceTrust()).isFalse();
  }

  @Test
  void throwsWhenTheAccountDoesNotExist() {
    AccountId missing = AccountId.newId();
    when(accounts.findById(missing)).thenReturn(Optional.empty());
    UpdateAccountPermissionsCommand command =
        new UpdateAccountPermissionsCommand(missing, true, true, ACTOR);

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(auditEvents);
  }
}
