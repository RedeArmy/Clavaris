package com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture;

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
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.InvalidProfilePictureException;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureValidator;
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

class UpdatePlatformAccountProfilePictureServiceTest {

  private PlatformAccountRepository accounts;
  private ProfilePictureStorage storage;
  private AuditEventRecorder auditEvents;
  private UpdatePlatformAccountProfilePictureService service;
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
        new UpdatePlatformAccountProfilePictureService(
            accounts, storage, auditEvents, fakeTransactionTemplate);
  }

  private UpdatePlatformAccountProfilePictureCommand command(
      final byte[] content, final String contentType) {
    return new UpdatePlatformAccountProfilePictureCommand(account.id(), content, contentType);
  }

  @Test
  void uploadsAndSavesTheNewPictureUrl() {
    when(storage.upload(eq("platform-avatars/" + account.id().value()), any(), eq("image/png")))
        .thenReturn("platform-avatars/" + account.id().value());

    UpdatePlatformAccountProfilePictureResult result =
        service.handle(command(new byte[] {1, 2, 3}, "image/png"));

    assertThat(result.storageKey()).isEqualTo("platform-avatars/" + account.id().value());
    verify(accounts).save(account);
    assertThat(account.pictureUrl()).contains("platform-avatars/" + account.id().value());
    verify(auditEvents)
        .write(
            any(),
            eq("platform_account.profile_picture_updated"),
            eq("PlatformAccount"),
            any(),
            eq(null));
  }

  @Test
  void rejectsAnUnsupportedContentType() {
    assertThatExceptionOfType(InvalidProfilePictureException.class)
        .isThrownBy(() -> service.handle(command(new byte[] {1}, "image/svg+xml")));

    verifyNoInteractions(storage);
    verify(accounts, never()).save(any());
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

    assertThatExceptionOfType(PlatformAccountNotFoundException.class)
        .isThrownBy(() -> service.handle(command(new byte[] {1}, "image/png")));
  }
}
