package com.clavaris.identity.application.usecase.deleteaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.event.AccountDeletedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeleteAccountServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private AccountRepository accounts;
  private AccountTokenRevoker accountTokenRevoker;
  private AccountSessionRevoker accountSessionRevoker;
  private WorkspaceMembershipEraser workspaceMembershipEraser;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private DeleteAccountService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    accountTokenRevoker = mock(AccountTokenRevoker.class);
    accountSessionRevoker = mock(AccountSessionRevoker.class);
    workspaceMembershipEraser = mock(WorkspaceMembershipEraser.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service =
        new DeleteAccountService(
            accounts,
            accountTokenRevoker,
            accountSessionRevoker,
            workspaceMembershipEraser,
            auditEvents,
            outbox);
  }

  @Test
  void revokesTokensWritesAuditAndOutboxEventsThenDeletesTheAccount() {
    Account account = registeredAccount();

    service.handle(new DeleteAccountCommand(account.id(), ACTOR));

    verify(accountTokenRevoker).revokeAllTokensFor(account.id());
    verify(accountSessionRevoker).revokeAllSessionsFor(account.id());
    verify(workspaceMembershipEraser).eraseAllMembershipsFor(account.id());
    verify(auditEvents)
        .write(ACTOR, "account.deleted", "Account", account.id().value().toString(), null);

    // AccountDeletedEvent.from(...)'s own Instant.now() call means the outbox payload can never
    // be asserted with a plain equals() against a value built here — captured and checked
    // field-by-field instead.
    ArgumentCaptor<AccountDeletedEvent> event = ArgumentCaptor.forClass(AccountDeletedEvent.class);
    verify(outbox).write(eq("account.deleted"), eq(account.id()), any(), event.capture());
    assertThat(event.getValue().accountId()).isEqualTo(account.id());
    assertThat(event.getValue().organizationId()).isEqualTo(account.organizationId());
    assertThat(event.getValue().email()).isEqualTo(account.email().value());
    assertThat(event.getValue().occurredAt()).isNotNull();
  }

  @Test
  void neverLeaksTheRawEmailIntoTheAuditDetail() {
    // BR-DATA-01: the fourth auditEvents.write(...) argument (detail) must never carry PII —
    // asserted directly, not just implied by the exact-match verify() above (which would also
    // pass if this class started passing a *different* string that happened to include the
    // email, as long as nothing else ever caught it).
    Account account = registeredAccount();

    service.handle(new DeleteAccountCommand(account.id(), ACTOR));

    ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
    verify(auditEvents)
        .write(eq(ACTOR), eq("account.deleted"), eq("Account"), anyString(), detail.capture());
    assertThat(detail.getValue()).isNull();
  }

  @Test
  void deletesTheAccountRowItselfAfterTheRevocationAndAuditSteps() {
    Account account = registeredAccount();

    service.handle(new DeleteAccountCommand(account.id(), ACTOR));

    verify(accounts).deleteById(account.id());
  }

  @Test
  void rejectsAnUnknownAccountWithoutRevokingAnythingOrRecordingAnything() {
    AccountId unknownAccountId = AccountId.newId();
    when(accounts.findById(unknownAccountId)).thenReturn(Optional.empty());
    DeleteAccountCommand command = new DeleteAccountCommand(unknownAccountId, ACTOR);

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(accountTokenRevoker);
    verifyNoInteractions(accountSessionRevoker);
    verifyNoInteractions(workspaceMembershipEraser);
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
    verify(accounts, never()).deleteById(any());
  }

  private Account registeredAccount() {
    Account account =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("gone@example.com"));
    account.attachPasswordCredential("argon2id$hashed");
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    return account;
  }
}
