package com.clavaris.identity.application.usecase.deleteaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.event.AccountDeletedEvent;
import com.clavaris.identity.domain.model.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-DATA-02/03: {@code POST /api/v1/admin/accounts/{id}:delete} — a real, permanent hard delete,
 * never anonymization, called by a consuming application (e.g. JobSeeker, its own ADR-0013) once
 * that application's own local data-handling process has already completed. Clavaris runs no
 * independent grace period of its own; this call is treated as final.
 *
 * <p>{@code accounts.deleteById} (migration {@code V20260826100000}) cascades at the database level
 * to {@code password_credentials}/{@code sessions}/{@code refresh_tokens}/{@code
 * verification_tokens} — every table whose only reason to exist is this one {@code Account}'s own
 * data goes with it structurally, not via an application-layer delete-list a future new table could
 * be left off of. {@link AccountTokenRevoker} is called explicitly first, not left to the cascade,
 * because it can't reach it: SAS's own {@code oauth2_authorization} table has no FK relationship to
 * {@code accounts} at all (same reasoning {@code ConfirmPasswordResetService}'s own identical call
 * already established for BR-ID-04's cascade).
 *
 * <p><b>Deliberately narrower than `security-architecture.md` §7's own 4-step design today</b>:
 * that design's steps 2 (remove `WorkspaceMembership` rows) and 3 (delete `SocialIdentity` links)
 * describe features that don't exist in this codebase yet (`Workspace`/social login, both v1-scoped
 * but zero code — CLAUDE.md §11). Nothing to clean up where nothing exists yet; this class must be
 * revisited the day either one ships, not silently forgotten (TD-FUT-005 already names the
 * workspace half of that obligation for its own reasons; this is the same trigger reaching this
 * service too).
 */
public class DeleteAccountService implements DeleteAccountUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(DeleteAccountService.class);

  private final AccountRepository accounts;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as every
  // other caller of this port (RotateRefreshTokenService, ConfirmPasswordResetService).
  private final AccountTokenRevoker accountTokenRevoker;

  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public DeleteAccountService(
      final AccountRepository accounts,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.accounts = accounts;
    this.accountTokenRevoker = accountTokenRevoker;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  // PMD.GuardLogStatement false positive — same rationale as every other logging call site in
  // this module (e.g. AuthenticateWithPasswordService's own identical suppression).
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public void handle(final DeleteAccountCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    // BR-DATA-03: "all sessions and refresh tokens are revoked immediately" — the SAS-managed
    // access/ID token half of that; the identity-module-owned Session/RefreshToken rows are
    // handled more strongly, below, by the hard delete itself (removed entirely, not just marked
    // revoked, since nothing here has any reason to retain a revoked-but-present row once the
    // account it belongs to is permanently gone).
    accountTokenRevoker.revokeAllTokensFor(account.id());

    // BR-DATA-01: never the raw email in the audit detail or the log line — same discipline as
    // every other audited action in this codebase.
    auditEvents.write(
        command.actor(), "account.deleted", "Account", account.id().value().toString(), null);

    outbox.write("account.deleted", account.id(), AccountDeletedEvent.from(account));

    // Captured before the delete, same reasoning as AccountDeletedEvent's own Javadoc — after
    // this call the row this log line describes no longer exists to re-read.
    LOG.info(
        "event=account_deleted organizationId={} accountId={}",
        account.organizationId(),
        account.id());

    accounts.deleteById(account.id());
  }
}
