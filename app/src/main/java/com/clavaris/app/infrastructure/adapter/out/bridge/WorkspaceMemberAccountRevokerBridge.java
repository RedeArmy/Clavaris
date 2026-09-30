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
// PMD.AvoidDuplicateLiterals: the repeated string is "PMD.LongVariable" itself, used on both the
// fields and the constructor's port parameters — same rationale AccountRevocationCascade's own
// identical class-level suppression documents for this exact PMD-annotation-string-as-literal
// false positive.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@Component
class WorkspaceMemberAccountRevokerBridge implements WorkspaceMemberAccountRevoker {

  private final SessionRepository sessions;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent
  // AccountRevocationCascade's own identical field already establishes.
  private final AccountTokenRevoker accountTokenRevoker;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as
  // accountTokenRevoker above.
  private final AccountSessionRevoker accountSessionRevoker;

  /* package */ WorkspaceMemberAccountRevokerBridge(
      final SessionRepository sessions,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker) {
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
