package com.clavaris.identity.application.usecase.suspendplatformaccount;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.confirmplatformaccountpasswordreset.PlatformAccountSessionRevoker;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.PlatformAccount;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link SuspendPlatformAccountUseCase}. Reuses {@link
 * PlatformAccountSessionRevoker} — the exact same session-revocation port {@code
 * ConfirmPlatformAccountPasswordResetService}'s own BR-ID-04-equivalent response already calls —
 * rather than a second, drifting copy of that cascade. See this interface's own Javadoc for why
 * there is no refresh-token/OAuth-session cascade or outbox write to also run here, unlike the
 * tenant-tier {@code SuspendAccountService} this class mirrors.
 */
public class SuspendPlatformAccountService implements SuspendPlatformAccountUseCase {

  private final PlatformAccountRepository accounts;
  private final PlatformAccountSessionRevoker sessionRevoker;
  private final AuditEventRecorder auditEvents;

  public SuspendPlatformAccountService(
      final PlatformAccountRepository accounts,
      final PlatformAccountSessionRevoker sessionRevoker,
      final AuditEventRecorder auditEvents) {
    this.accounts = accounts;
    this.sessionRevoker = sessionRevoker;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final SuspendPlatformAccountCommand command) {
    final PlatformAccount account =
        accounts
            .findById(command.platformAccountId())
            .orElseThrow(() -> new PlatformAccountNotFoundException(command.platformAccountId()));

    account.suspend();
    accounts.save(account);

    sessionRevoker.revokeAllSessionsFor(account.id());

    auditEvents.write(
        command.actor(),
        "platform_account.suspended",
        "PlatformAccount",
        account.id().value().toString(),
        null);
  }
}
