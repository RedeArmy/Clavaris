package com.clavaris.identity.application.usecase.removeplatformaccountprofilepicture;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture.PlatformAccountNotFoundException;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.service.ProfilePictureReference;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code removeaccountprofilepicture.RemoveAccountProfilePictureService}'s platform-tier sibling —
 * same shape, same "clear the reference first, delete the object after, skip external URLs"
 * discipline, see that class's own Javadoc for the full rationale.
 */
@SuppressWarnings("PMD.LongVariable")
public class RemovePlatformAccountProfilePictureService
    implements RemovePlatformAccountProfilePictureUseCase {

  private final PlatformAccountRepository accounts;
  private final ProfilePictureStorage storage;
  private final AuditEventRecorder auditEvents;
  private final TransactionTemplate transactionTemplate;

  public RemovePlatformAccountProfilePictureService(
      final PlatformAccountRepository accounts,
      final ProfilePictureStorage storage,
      final AuditEventRecorder auditEvents,
      final TransactionTemplate transactionTemplate) {
    this.accounts = accounts;
    this.storage = storage;
    this.auditEvents = auditEvents;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public void handle(final PlatformAccountId platformAccountId) {
    final PlatformAccount account =
        accounts
            .findById(platformAccountId)
            .orElseThrow(() -> new PlatformAccountNotFoundException(platformAccountId));
    final String previousPictureUrl = account.pictureUrl().orElse(null);

    transactionTemplate.executeWithoutResult(
        status -> {
          account.removeProfilePicture();
          accounts.save(account);
          auditEvents.write(
              AuditActor.platformAccount(account.id().value()),
              "platform_account.profile_picture_removed",
              "PlatformAccount",
              account.id().value().toString(),
              null);
        });

    if (previousPictureUrl != null && !ProfilePictureReference.isExternalUrl(previousPictureUrl)) {
      storage.delete(previousPictureUrl);
    }
  }
}
