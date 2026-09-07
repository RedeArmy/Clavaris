package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.organization.application.usecase.removeworkspacemember.WorkspaceMemberRefreshTokenRevoker;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's {@link WorkspaceMemberRefreshTokenRevoker} — the bridge lives in
 * {@code app}, not either business module, same module-graph reason {@code
 * WorkspaceMemberAccountProvisionerBridge} already establishes.
 *
 * <p>TD-WS-002 mitigation: 100% reuse of identity-module's own already-built, already-tested BR-ID-
 * 03 revocation ({@code RefreshTokenRepository#revokeAllActiveForAccount}) — {@code
 * JpaRefreshTokenRepository} is already a Spring bean visible across this single deployable's whole
 * context, so this bridge needs no new identity-module use case, just this one delegation.
 */
@Component
class WorkspaceMemberRefreshTokenRevokerBridge implements WorkspaceMemberRefreshTokenRevoker {

  private final RefreshTokenRepository refreshTokens;

  /* package */ WorkspaceMemberRefreshTokenRevokerBridge(
      final RefreshTokenRepository refreshTokens) {
    this.refreshTokens = refreshTokens;
  }

  @Override
  public void revokeAllRefreshTokensFor(final UUID accountId) {
    refreshTokens.revokeAllActiveForAccount(new AccountId(accountId));
  }
}
