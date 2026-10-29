package com.clavaris.identity.application.usecase.chooseaccountusername;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.updateaccountprofile.AccountNotFoundException;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ChooseAccountUsernameServiceTest {

  private AccountRepository accounts;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private ChooseAccountUsernameService service;
  private Account account;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service = new ChooseAccountUsernameService(accounts, auditEvents, outbox);
    account = Account.register(new OrganizationId(UUID.randomUUID()), new Email("ada@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
  }

  private ChooseAccountUsernameCommand command(final String raw) {
    return new ChooseAccountUsernameCommand(
        account.id(), raw, AuditActor.account(account.id().value()));
  }

  @Test
  void assignsTheChosenUsernameAndRecordsItAndPublishesTheProfileEvent() {
    service.handle(command("Ada_Lovelace"));

    assertThat(account.username()).map(Username::value).contains("ada_lovelace");
    verify(accounts).save(account);
    verify(auditEvents)
        .write(
            any(),
            eq("account.username_chosen"),
            eq("Account"),
            eq(account.id().value().toString()),
            any());
    verify(outbox).write(eq("account.profile_updated"), eq(account.id()), any(), any());
  }

  @Test
  void rejectsAUsernameAnotherAccountOfTheSameOrganizationAlreadyHas() {
    when(accounts.existsByOrganizationIdAndUsername(
            eq(account.organizationId()), eq(new Username("taken"))))
        .thenReturn(true);

    assertThatExceptionOfType(UsernameAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command("taken")));

    assertThat(account.username()).isEmpty();
    verify(accounts, never()).save(any());
    verify(outbox, never()).write(any(), any(), any(), any());
  }

  @Test
  void reportsALostRaceOnTheUniqueIndexTheSameWay() {
    doThrow(new DataIntegrityViolationException("ux_accounts_organization_id_username"))
        .when(accounts)
        .save(account);

    assertThatExceptionOfType(UsernameAlreadyRegisteredException.class)
        .isThrownBy(() -> service.handle(command("racing")));

    verify(auditEvents, never()).write(any(), any(), any(), any(), any());
  }

  @Test
  void rejectsAShapeTheUsernameRuleDoesNotAllow() {
    assertThatIllegalArgumentException().isThrownBy(() -> service.handle(command("no spaces")));
    assertThatIllegalArgumentException().isThrownBy(() -> service.handle(command("ab")));

    verify(accounts, never()).save(any());
  }

  @Test
  void neverOverwritesAUsernameThatIsAlreadySet() {
    account.assignUsername(new Username("first_choice"));

    service.handle(command("second_choice"));

    assertThat(account.username()).map(Username::value).contains("first_choice");
    verify(accounts, never()).save(any());
    verify(outbox, never()).write(any(), any(), any(), any());
  }

  @Test
  void failsForAnAccountThatDoesNotExist() {
    final Account other =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("ghost@example.com"));
    when(accounts.findById(other.id())).thenReturn(Optional.empty());

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(
            () ->
                service.handle(
                    new ChooseAccountUsernameCommand(
                        other.id(), "someone", AuditActor.account(other.id().value()))));
  }
}
