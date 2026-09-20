package com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureValidator;
import com.clavaris.identity.domain.model.PlatformAccount;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code updateaccountprofilepicture.UpdateAccountProfilePictureService}'s platform-tier sibling —
 * same shape, same "validate, upload outside any transaction, then persist + audit inside one"
 * discipline, see that class's own Javadoc for the full rationale.
 */
@SuppressWarnings("PMD.LongVariable")
public class UpdatePlatformAccountProfilePictureService
    implements UpdatePlatformAccountProfilePictureUseCase {

  // ADR-0026: deliberately a different prefix from tenant Account's own "avatars/" — the two
  // aggregates' ids are drawn from unrelated UUID spaces, but a shared prefix would still make an
  // accidental key collision a code-review-only guard rather than a structural one.
  private static final String KEY_PREFIX = "platform-avatars/";

  private final PlatformAccountRepository accounts;
  private final ProfilePictureStorage storage;
  private final AuditEventRecorder auditEvents;
  private final TransactionTemplate transactionTemplate;

  public UpdatePlatformAccountProfilePictureService(
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
  public UpdatePlatformAccountProfilePictureResult handle(
      final UpdatePlatformAccountProfilePictureCommand command) {
    ProfilePictureValidator.validate(command.content(), command.contentType());

    final PlatformAccount account =
        accounts
            .findById(command.platformAccountId())
            .orElseThrow(() -> new PlatformAccountNotFoundException(command.platformAccountId()));

    final String storageKey =
        storage.upload(
            KEY_PREFIX + command.platformAccountId().value(),
            command.content(),
            command.contentType());

    transactionTemplate.executeWithoutResult(
        status -> {
          account.updateProfilePicture(storageKey);
          accounts.save(account);
          auditEvents.write(
              AuditActor.platformAccount(account.id().value()),
              "platform_account.profile_picture_updated",
              "PlatformAccount",
              account.id().value().toString(),
              null);
        });
    return new UpdatePlatformAccountProfilePictureResult(storageKey);
  }
}
