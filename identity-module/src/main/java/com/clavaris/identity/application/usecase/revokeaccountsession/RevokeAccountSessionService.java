package com.clavaris.identity.application.usecase.revokeaccountsession;

import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;

/**
 * Orchestration for {@link RevokeAccountSessionUseCase}. Reuses {@code
 * listactivesessionsforaccount}'s own {@link AccountActiveSessionsRepository} rather than a
 * dedicated port — same "one port, several use cases" precedent as {@code AccountRepository}.
 */
public class RevokeAccountSessionService implements RevokeAccountSessionUseCase {

  private final AccountActiveSessionsRepository activeSessions;

  public RevokeAccountSessionService(final AccountActiveSessionsRepository activeSessions) {
    this.activeSessions = activeSessions;
  }

  @Override
  public void handle(final RevokeAccountSessionCommand command) {
    // The ownership check itself: command.accountId() is always the caller's own session
    // principal (never client input), so this lookup can only ever resolve a session if it both
    // exists and belongs to the caller — same outcome (SessionNotFoundException) either way a
    // mismatched sessionId fails, by design (see that exception's own Javadoc).
    final ActiveAccountSession session =
        activeSessions
            .findByAccountIdAndSessionId(command.accountId(), command.sessionId())
            .orElseThrow(() -> new SessionNotFoundException(command.sessionId()));

    activeSessions.revoke(session.sessionId());
  }
}
