package com.clavaris.identity.application.usecase.updateaccountmetadata;

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
import tools.jackson.databind.ObjectMapper;

class UpdateAccountMetadataServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private AccountRepository accounts;
  private AuditEventRecorder auditEvents;
  private UpdateAccountMetadataService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    service = new UpdateAccountMetadataService(accounts, auditEvents, new ObjectMapper());
  }

  private Account registeredAccount() {
    Account account =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("meta@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    return account;
  }

  @Test
  void replacesAllThreeTiersAndPersists() {
    Account account = registeredAccount();

    service.handle(
        new UpdateAccountMetadataCommand(
            account.id(), "{\"a\":1}", "{\"b\":2}", "{\"c\":3}", ACTOR));

    assertThat(account.publicMetadata()).contains("{\"a\":1}");
    assertThat(account.privateMetadata()).contains("{\"b\":2}");
    assertThat(account.unsafeMetadata()).contains("{\"c\":3}");
    verify(accounts).save(account);
  }

  @Test
  void blankOrNullTiersAreClearedNotRejected() {
    Account account = registeredAccount();

    service.handle(new UpdateAccountMetadataCommand(account.id(), "  ", null, "{\"c\":3}", ACTOR));

    assertThat(account.publicMetadata()).isEmpty();
    assertThat(account.privateMetadata()).isEmpty();
    assertThat(account.unsafeMetadata()).contains("{\"c\":3}");
  }

  @Test
  void recordsAnAuditEventWithoutTheMetadataContentItself() {
    Account account = registeredAccount();

    service.handle(
        new UpdateAccountMetadataCommand(account.id(), "{\"secret\":true}", null, null, ACTOR));

    verify(auditEvents)
        .write(ACTOR, "account.metadata_updated", "Account", account.id().value().toString(), null);
  }

  @Test
  void rejectsSyntacticallyInvalidJsonForAnyTier() {
    // Validation runs before the repository lookup, so no account needs to exist for this case.
    UpdateAccountMetadataCommand command =
        new UpdateAccountMetadataCommand(AccountId.newId(), "not json", null, null, ACTOR);

    assertThatExceptionOfType(InvalidMetadataException.class)
        .isThrownBy(() -> service.handle(command))
        .satisfies(e -> assertThat(e.tier()).isEqualTo("publicMetadata"));

    verify(accounts, never()).save(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void rejectsATierExceedingTheMaxLength() {
    String tooLong =
        "{\"x\":\"" + "a".repeat(UpdateAccountMetadataService.MAX_METADATA_LENGTH) + "\"}";
    UpdateAccountMetadataCommand command =
        new UpdateAccountMetadataCommand(AccountId.newId(), null, tooLong, null, ACTOR);

    assertThatExceptionOfType(InvalidMetadataException.class)
        .isThrownBy(() -> service.handle(command))
        .satisfies(e -> assertThat(e.tier()).isEqualTo("privateMetadata"));
  }

  @Test
  void rejectsAnUnknownAccountWithoutRecordingAnything() {
    AccountId unknownAccountId = AccountId.newId();
    when(accounts.findById(unknownAccountId)).thenReturn(Optional.empty());
    UpdateAccountMetadataCommand command =
        new UpdateAccountMetadataCommand(unknownAccountId, null, null, null, ACTOR);

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
    verifyNoInteractions(auditEvents);
  }
}
