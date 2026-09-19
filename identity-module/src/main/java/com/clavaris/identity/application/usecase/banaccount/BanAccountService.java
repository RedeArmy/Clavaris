package com.clavaris.identity.application.usecase.banaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.event.AccountBannedEvent;
import com.clavaris.identity.domain.model.Account;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link BanAccountUseCase}. SDE-III review, 2026-09-19 — Clerk dashboard "Users"
 * parity: byte-for-byte the same revocation cascade {@code suspendaccount.SuspendAccountService}
 * already established (see that class's own Javadoc for why each of these four calls exists),
 * applied to the {@code ban()} transition instead of {@code suspend()} — a deliberately separate
 * use case, not a reuse, per {@code AccountStatus}'s own Javadoc.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class BanAccountService implements BanAccountUseCase {

  private final AccountRepository accounts;
  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;

  @SuppressWarnings("PMD.LongVariable")
  private final AccountTokenRevoker accountTokenRevoker;

  @SuppressWarnings("PMD.LongVariable")
  private final AccountSessionRevoker accountSessionRevoker;

  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  @SuppressWarnings("java:S107")
  public BanAccountService(
      final AccountRepository accounts,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.accounts = accounts;
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.accountTokenRevoker = accountTokenRevoker;
    this.accountSessionRevoker = accountSessionRevoker;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public void handle(final BanAccountCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    account.ban();
    accounts.save(account);

    sessions.revokeAllActiveForAccount(account.id());
    refreshTokens.revokeAllActiveForAccount(account.id());
    accountTokenRevoker.revokeAllTokensFor(account.id());
    accountSessionRevoker.revokeAllSessionsFor(account.id());

    auditEvents.write(
        command.actor(), "account.banned", "Account", account.id().value().toString(), null);

    outbox.write(
        "account.banned", account.id(), account.organizationId(), AccountBannedEvent.from(account));
  }
}
