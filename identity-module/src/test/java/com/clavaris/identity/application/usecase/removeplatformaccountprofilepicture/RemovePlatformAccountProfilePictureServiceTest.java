package com.clavaris.identity.application.usecase.removeplatformaccountprofilepicture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture.PlatformAccountNotFoundException;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class RemovePlatformAccountProfilePictureServiceTest {

  private PlatformAccountRepository accounts;
  private ProfilePictureStorage storage;
  private AuditEventRecorder auditEvents;
  private RemovePlatformAccountProfilePictureService service;
  private PlatformAccount account;

  @BeforeEach
  void setUp() {
    accounts = mock(PlatformAccountRepository.class);
    storage = mock(ProfilePictureStorage.class);
    auditEvents = mock(AuditEventRecorder.class);
    account =
        PlatformAccount.reconstitute(
            PlatformAccountId.newId(),
            new Email("operator@example.com"),
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
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
        new RemovePlatformAccountProfilePictureService(
            accounts, storage, auditEvents, fakeTransactionTemplate);
  }

  @Test
  void clearsAStoredPictureAndDeletesTheUnderlyingObject() {
    account.updateProfilePicture("platform-avatars/" + account.id().value());

    service.handle(account.id());

    assertThat(account.pictureUrl()).isEmpty();
    verify(accounts).save(account);
    verify(storage).delete("platform-avatars/" + account.id().value());
    verify(auditEvents)
        .write(
            any(),
            eq("platform_account.profile_picture_removed"),
            eq("PlatformAccount"),
            any(),
            eq(null));
  }

  @Test
  void clearsAnExternalPictureWithoutTouchingStorage() {
    account.updateProfilePicture("https://example.com/avatar.png");

    service.handle(account.id());

    verify(storage, never()).delete(any());
  }

  @Test
  void throwsWhenTheAccountDoesNotExist() {
    when(accounts.findById(any())).thenReturn(Optional.empty());
    PlatformAccountId missing = PlatformAccountId.newId();

    assertThatExceptionOfType(PlatformAccountNotFoundException.class)
        .isThrownBy(() -> service.handle(missing));
  }
}
