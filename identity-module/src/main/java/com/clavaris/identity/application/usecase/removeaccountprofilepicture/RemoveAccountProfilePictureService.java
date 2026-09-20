package com.clavaris.identity.application.usecase.removeaccountprofilepicture;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.service.ProfilePictureReference;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestration for {@link RemoveAccountProfilePictureUseCase}. Clears the Account's own reference
 * first, then best-effort deletes the underlying storage object afterward (never the reverse order
 * — deleting the object first and then failing to clear the reference would leave {@code
 * Account.pictureUrl} pointing at nothing, which {@code GetAccountAvatarService} would then fail to
 * serve; a reference cleared but an orphaned storage object left behind is merely wasted storage,
 * not a broken avatar). Nothing to delete at all when the picture was a social provider's own
 * external URL, never a Clavaris-managed upload — {@link ProfilePictureReference#isExternalUrl}
 * makes that distinction, same as {@code GetAccountAvatarService}'s own read-side check.
 */
// PMD.LongVariable: transactionTemplate/previousPictureUrl name exactly what they are — same
// convention AuthenticateWithSocialProviderService's own identical class-level suppression
// documents for this exact field.
@SuppressWarnings("PMD.LongVariable")
public class RemoveAccountProfilePictureService implements RemoveAccountProfilePictureUseCase {

  private final AccountRepository accounts;
  private final ProfilePictureStorage storage;
  private final AuditEventRecorder auditEvents;
  private final TransactionTemplate transactionTemplate;

  public RemoveAccountProfilePictureService(
      final AccountRepository accounts,
      final ProfilePictureStorage storage,
      final AuditEventRecorder auditEvents,
      final TransactionTemplate transactionTemplate) {
    this.accounts = accounts;
    this.storage = storage;
    this.auditEvents = auditEvents;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public void handle(final RemoveAccountProfilePictureCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));
    final String previousPictureUrl = account.pictureUrl().orElse(null);

    transactionTemplate.executeWithoutResult(
        status -> {
          account.removeProfilePicture();
          accounts.save(account);
          auditEvents.write(
              command.actor(),
              "account.profile_picture_removed",
              "Account",
              account.id().value().toString(),
              null);
        });

    // Deliberately after the transaction above has already committed — the actual network call
    // to the storage backend must never run inside an open database transaction, same discipline
    // UpdateAccountProfilePictureService's own Javadoc documents.
    if (previousPictureUrl != null && !ProfilePictureReference.isExternalUrl(previousPictureUrl)) {
      storage.delete(previousPictureUrl);
    }
  }
}
