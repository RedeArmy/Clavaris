package com.clavaris.identity.application.usecase.updateaccountprofile;

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
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.domain.event.AccountProfileUpdatedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdateAccountProfileServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private AccountRepository accounts;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private UpdateAccountProfileService service;
  private Account account;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service = new UpdateAccountProfileService(accounts, auditEvents, outbox);
    account = Account.register(new OrganizationId(UUID.randomUUID()), new Email("ada@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
  }

  @Test
  void updatesTheNameSavesAndAudits() {
    service.handle(
        new UpdateAccountProfileCommand(account.id(), "Ada", "Lovelace", null, null, ACTOR));

    assertThat(account.firstName()).contains("Ada");
    assertThat(account.lastName()).contains("Lovelace");
    verify(accounts).save(account);
    verify(auditEvents)
        .write(ACTOR, "account.profile_updated", "Account", account.id().value().toString(), null);
  }

  // TD-FUT-044: the "both directions" half of profile sync — a webhook fires in the same
  // transaction as the save, for every caller (self-service, operator, Backend-API) that reaches
  // this one shared method. The payload itself is asserted field-by-field, never by record
  // equality — AccountProfileUpdatedEvent.from(account) stamps its own occurredAt via Instant.now()
  // at construction time, so two separately-built instances are never equal even for the same
  // Account, same reason RegisterAccountServiceTest's own identical outbox assertion uses any()
  // for the payload rather than an exact record match.
  @Test
  void writesAccountProfileUpdatedToTheOutbox() {
    service.handle(
        new UpdateAccountProfileCommand(account.id(), "Ada", "Lovelace", null, null, ACTOR));

    org.mockito.ArgumentCaptor<AccountProfileUpdatedEvent> event =
        org.mockito.ArgumentCaptor.forClass(AccountProfileUpdatedEvent.class);
    verify(outbox)
        .write(
            eq("account.profile_updated"),
            eq(account.id()),
            eq(account.organizationId()),
            event.capture());
    assertThat(event.getValue().firstName()).isEqualTo("Ada");
    assertThat(event.getValue().lastName()).isEqualTo("Lovelace");
    assertThat(event.getValue().accountId()).isEqualTo(account.id());
    assertThat(event.getValue().organizationId()).isEqualTo(account.organizationId());
  }

  @Test
  void throwsWhenTheAccountDoesNotExist() {
    AccountId missing = AccountId.newId();
    when(accounts.findById(missing)).thenReturn(Optional.empty());
    UpdateAccountProfileCommand command =
        new UpdateAccountProfileCommand(missing, "Ada", "Lovelace", null, null, ACTOR);

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  // Live feature request, 2026-09-22: only applied while the Account has no phone number yet —
  // see UpdateAccountProfileCommand's own Javadoc for why this mirrors username's own posture at
  // the user's explicit request, not a pre-existing domain invariant.
  @Test
  void setsThePhoneNumberWhenTheAccountHasNoneYet() {
    service.handle(
        new UpdateAccountProfileCommand(account.id(), null, null, null, "+1-555-0100", ACTOR));

    assertThat(account.phoneNumber()).contains("+1-555-0100");
    verify(accounts).save(account);
  }

  @Test
  void silentlyIgnoresASubmittedPhoneNumberWhenTheAccountAlreadyHasOne() {
    account.updatePhoneNumber("+1-555-0000");

    service.handle(
        new UpdateAccountProfileCommand(account.id(), null, null, null, "+1-555-9999", ACTOR));

    assertThat(account.phoneNumber()).contains("+1-555-0000");
    verify(accounts).save(account);
  }

  // Live feature request, 2026-09-22: this is the "assign only, never a real change" behavior the
  // user explicitly chose over relitigating Account.assignUsername's own write-once invariant
  // (ADR-0024 §4) — see UpdateAccountProfileCommand's own Javadoc for the full rationale.
  @Test
  void assignsAUsernameWhenTheAccountHasNoneYet() {
    when(accounts.existsByOrganizationIdAndUsername(any(), any())).thenReturn(false);

    service.handle(
        new UpdateAccountProfileCommand(account.id(), null, null, "ada-lovelace", null, ACTOR));

    assertThat(account.username()).contains(new Username("ada-lovelace"));
    verify(accounts).save(account);
  }

  @Test
  void throwsWhenTheSubmittedUsernameIsAlreadyTakenByAnotherAccountInTheSameOrganization() {
    when(accounts.existsByOrganizationIdAndUsername(
            account.organizationId(), new Username("taken")))
        .thenReturn(true);
    UpdateAccountProfileCommand command =
        new UpdateAccountProfileCommand(account.id(), null, null, "taken", null, ACTOR);

    assertThatExceptionOfType(UsernameAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command));

    assertThat(account.username()).isEmpty();
    verify(accounts, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  @Test
  void silentlyIgnoresASubmittedUsernameWhenTheAccountAlreadyHasOne() {
    account.assignUsername(new Username("already-set"));

    service.handle(
        new UpdateAccountProfileCommand(account.id(), null, null, "attempted-change", null, ACTOR));

    assertThat(account.username()).contains(new Username("already-set"));
    verify(accounts, never()).existsByOrganizationIdAndUsername(any(), any());
    verify(accounts).save(account);
  }
}
