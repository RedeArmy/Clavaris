package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestration for {@link UpdateAccountProfilePictureUseCase} — the pasted "Update profile,
 * recommended size 1:1, up to 10MB" UI copy's own backend. Validates ({@link
 * ProfilePictureValidator}) before ever calling {@link ProfilePictureStorage#upload} (a real,
 * immediate rejection with a clear reason beats a silent truncation or an opaque storage-layer
 * error), then updates the {@code Account} aggregate and persists it in the same transaction the
 * upload itself is not part of — the upload is a network call to Supabase, same "no network call
 * inside an open database transaction" discipline {@code AuthenticateWithSocialProviderService}'s
 * own Javadoc already documents for its own mail send.
 */
// PMD.LongVariable: transactionTemplate names exactly what it is — same convention
// AuthenticateWithSocialProviderService's own identical class-level suppression documents.
@SuppressWarnings("PMD.LongVariable")
public class UpdateAccountProfilePictureService implements UpdateAccountProfilePictureUseCase {

  // ADR-0026: this Account's own stable storage key — key CONSTRUCTION lives here, the caller,
  // not in ProfilePictureStorage/its Supabase adapter, which no longer knows any
  // account-type-specific key scheme at all (see that port's own Javadoc for why this moved).
  private static final String KEY_PREFIX = "avatars/";

  private final AccountRepository accounts;
  private final ProfilePictureStorage storage;
  private final AuditEventRecorder auditEvents;
  private final TransactionTemplate transactionTemplate;

  public UpdateAccountProfilePictureService(
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
  public UpdateAccountProfilePictureResult handle(
      final UpdateAccountProfilePictureCommand command) {
    ProfilePictureValidator.validate(command.content(), command.contentType());

    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    // The actual network call to the storage backend happens here, deliberately outside any open
    // database transaction — same "no network call inside an open transaction" discipline
    // AuthenticateWithSocialProviderService's own Javadoc documents for its own mail send.
    final String storageKey =
        storage.upload(
            KEY_PREFIX + command.accountId().value(), command.content(), command.contentType());

    transactionTemplate.executeWithoutResult(
        status -> {
          account.updateProfilePicture(storageKey);
          accounts.save(account);
          auditEvents.write(
              command.actor(),
              "account.profile_picture_updated",
              "Account",
              account.id().value().toString(),
              null);
        });
    return new UpdateAccountProfilePictureResult(storageKey);
  }
}
