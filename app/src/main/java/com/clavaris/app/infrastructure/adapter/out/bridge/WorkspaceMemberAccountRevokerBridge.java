package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.organization.application.usecase.removeworkspacemember.WorkspaceMemberAccountRevoker;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's {@link WorkspaceMemberAccountRevoker} — the bridge lives in
 * {@code app}, not either business module, same module-graph reason {@code
 * WorkspaceMemberRefreshTokenRevokerBridge} already establishes.
 *
 * <p>TD-WS-002 (closed): 100% reuse of identity-module's own already-built, already-tested ports —
 * {@link SessionRepository#revokeAllActiveForAccount}, {@link AccountTokenRevoker}, {@link
 * AccountSessionRevoker} — same call order {@code AccountRevocationCascade} already uses for its
 * own comparable cascade, minus the refresh-token step ({@code
 * WorkspaceMemberRefreshTokenRevokerBridge} already covers that separately, unchanged).
 */
@Component
class WorkspaceMemberAccountRevokerBridge implements WorkspaceMemberAccountRevoker {

  private final SessionRepository sessions;

  private final AccountTokenRevoker accountTokenRevoker;

  private final AccountSessionRevoker accountSessionRevoker;

  /* package */ WorkspaceMemberAccountRevokerBridge(
      final SessionRepository sessions,
      final AccountTokenRevoker accountTokenRevoker,
      final AccountSessionRevoker accountSessionRevoker) {
    this.sessions = sessions;
    this.accountTokenRevoker = accountTokenRevoker;
    this.accountSessionRevoker = accountSessionRevoker;
  }

  @Override
  public void revokeAllAccessFor(final UUID accountId) {
    final AccountId resolvedAccountId = new AccountId(accountId);
    sessions.revokeAllActiveForAccount(resolvedAccountId);
    accountTokenRevoker.revokeAllTokensFor(resolvedAccountId);
    accountSessionRevoker.revokeAllSessionsFor(resolvedAccountId);
  }
}
