package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

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
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class UpdateAccountProfilePictureServiceTest {

  private AccountRepository accounts;
  private ProfilePictureStorage storage;
  private AuditEventRecorder auditEvents;
  private UpdateAccountProfilePictureService service;
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
            java.time.Instant.now(),
            null,
            com.clavaris.identity.domain.model.AccountStatus.ACTIVE,
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
        new UpdateAccountProfilePictureService(
            accounts, storage, auditEvents, fakeTransactionTemplate);
  }

  private UpdateAccountProfilePictureCommand command(
      final byte[] content, final String contentType) {
    return new UpdateAccountProfilePictureCommand(account.id(), content, contentType);
  }

  @Test
  void uploadsAndSavesTheNewPictureUrl() {
    when(storage.upload(eq("avatars/" + account.id().value()), any(), eq("image/png")))
        .thenReturn("avatars/" + account.id());

    UpdateAccountProfilePictureResult result =
        service.handle(command(new byte[] {1, 2, 3}, "image/png"));

    assertThat(result.storageKey()).isEqualTo("avatars/" + account.id());
    verify(accounts).save(account);
    assertThat(account.pictureUrl()).contains("avatars/" + account.id());
    verify(auditEvents)
        .write(any(), eq("account.profile_picture_updated"), eq("Account"), any(), eq(null));
  }

  @Test
  void rejectsAnUnsupportedContentType() {
    assertThatExceptionOfType(InvalidProfilePictureException.class)
        .isThrownBy(() -> service.handle(command(new byte[] {1}, "image/svg+xml")));

    verifyNoInteractions(storage);
    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsEmptyContent() {
    assertThatExceptionOfType(InvalidProfilePictureException.class)
        .isThrownBy(() -> service.handle(command(new byte[0], "image/png")));

    verifyNoInteractions(storage);
  }

  @Test
  void rejectsContentOverTheTenMegabyteCap() {
    byte[] tooLarge = new byte[(int) ProfilePictureValidator.MAX_BYTES + 1];

    assertThatExceptionOfType(InvalidProfilePictureException.class)
        .isThrownBy(() -> service.handle(command(tooLarge, "image/png")));

    verifyNoInteractions(storage);
  }

  @Test
  void throwsWhenTheAccountDoesNotExist() {
    when(accounts.findById(any())).thenReturn(Optional.empty());

    assertThatExceptionOfType(AccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command(new byte[] {1}, "image/png")));
  }
}
