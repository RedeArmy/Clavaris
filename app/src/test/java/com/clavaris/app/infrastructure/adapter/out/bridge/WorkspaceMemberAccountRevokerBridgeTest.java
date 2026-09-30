package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.AccountId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TD-WS-002 (closed): proves the bridge's own delegation shape and call order, not any of the three
 * delegated ports themselves — each already has its own test coverage (identity-module's own {@code
 * JpaSessionRepositoryTest}, this package's own {@code AccountTokenRevokerBridgeTest}/{@code
 * AccountSessionRevokerBridgeTest}).
 */
class WorkspaceMemberAccountRevokerBridgeTest {

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final AccountTokenRevoker accountTokenRevoker = mock(AccountTokenRevoker.class);
  private final AccountSessionRevoker accountSessionRevoker = mock(AccountSessionRevoker.class);
  private final WorkspaceMemberAccountRevokerBridge bridge =
      new WorkspaceMemberAccountRevokerBridge(sessions, accountTokenRevoker, accountSessionRevoker);

  @Test
  void delegatesToAllThreeRevocationPortsWithTheSameAccountId() {
    UUID accountId = UUID.randomUUID();

    bridge.revokeAllAccessFor(accountId);

    AccountId expected = new AccountId(accountId);
    verify(sessions).revokeAllActiveForAccount(expected);
    verify(accountTokenRevoker).revokeAllTokensFor(expected);
    verify(accountSessionRevoker).revokeAllSessionsFor(expected);
  }
}
