package com.clavaris.app.infrastructure.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import org.junit.jupiter.api.Test;

class WorkspaceMembershipEraserBridgeTest {

  private final WorkspaceMembershipRepository memberships =
      mock(WorkspaceMembershipRepository.class);
  private final WorkspaceMembershipEraserBridge bridge =
      new WorkspaceMembershipEraserBridge(memberships);

  @Test
  void delegatesToTheRepositoryUsingTheAccountIdsRawUuid() {
    AccountId accountId = AccountId.newId();

    bridge.eraseAllMembershipsFor(accountId);

    verify(memberships).deleteAllByAccountId(accountId.value());
  }
}
