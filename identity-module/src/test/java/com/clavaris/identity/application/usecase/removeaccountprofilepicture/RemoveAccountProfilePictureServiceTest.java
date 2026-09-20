package com.clavaris.identity.application.usecase.removeaccountprofilepicture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class RemoveAccountProfilePictureServiceTest {

  private AccountRepository accounts;
  private ProfilePictureStorage storage;
  private AuditEventRecorder auditEvents;
  private RemoveAccountProfilePictureService service;
  private Account account;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    storage = mock(ProfilePictureStorage.class);
    auditEvents = mock(AuditEventRecorder.class);
    account =
        Account.reconstitute(
            AccountId.newId(),
            new OrganizationId(UUID.randomUUID()),
            new Email("user@example.com"),
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null,
            null,
            null);
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));

    PlatformTransactionManager fakeTransactionManager = mock(PlatformTransactionManager.class);
    TransactionTemplate fakeTransactionTemplate =
        new TransactionTemplate(fakeTransactionManager) {
          @Override
          public <T> T execute(final TransactionCallback<T> action) {
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
          }
        };

    service =
        new RemoveAccountProfilePictureService(
            accounts, storage, auditEvents, fakeTransactionTemplate);
  }

  @Test
  void clearsAStoredPictureAndDeletesTheUnderlyingObject() {
    account.updateProfilePicture("avatars/" + account.id());

    service.handle(new RemoveAccountProfilePictureCommand(account.id()));

    assertThat(account.pictureUrl()).isEmpty();
    verify(accounts).save(account);
    verify(storage).delete("avatars/" + account.id());
    verify(auditEvents)
        .write(any(), eq("account.profile_picture_removed"), eq("Account"), any(), eq(null));
  }

  @Test
  void clearsAnExternalPictureWithoutTouchingStorage() {
    account.updateProfilePicture("https://example.com/avatar.png");

    service.handle(new RemoveAccountProfilePictureCommand(account.id()));

    assertThat(account.pictureUrl()).isEmpty();
    verify(storage, never()).delete(any());
  }

  @Test
  void isANoOpOnStorageWhenNoPictureWasSet() {
    service.handle(new RemoveAccountProfilePictureCommand(account.id()));

    verify(storage, never()).delete(any());
    verify(accounts).save(account);
  }

  @Test
  void throwsWhenTheAccountDoesNotExist() {
    when(accounts.findById(any())).thenReturn(Optional.empty());
    RemoveAccountProfilePictureCommand command =
        new RemoveAccountProfilePictureCommand(AccountId.newId());

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
