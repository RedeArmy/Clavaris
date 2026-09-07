package com.clavaris.app.infrastructure.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.domain.model.AccountId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TD-WS-002 mitigation: proves the bridge's own delegation shape, not {@code
 * RefreshTokenRepository#revokeAllActiveForAccount} itself — that's already covered by
 * identity-module's own {@code JpaRefreshTokenRepositoryTest}/{@code
 * RotateRefreshTokenServiceTest}.
 */
class WorkspaceMemberRefreshTokenRevokerBridgeTest {

  private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
  private final WorkspaceMemberRefreshTokenRevokerBridge bridge =
      new WorkspaceMemberRefreshTokenRevokerBridge(refreshTokens);

  @Test
  void delegatesToRefreshTokenRepositoryWithTheSameAccountId() {
    UUID accountId = UUID.randomUUID();

    bridge.revokeAllRefreshTokensFor(accountId);

    verify(refreshTokens).revokeAllActiveForAccount(new AccountId(accountId));
  }
}
