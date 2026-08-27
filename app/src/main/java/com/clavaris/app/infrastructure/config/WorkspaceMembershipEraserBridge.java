package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.deleteaccount.WorkspaceMembershipEraser;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import org.springframework.stereotype.Component;

/**
 * Implements identity-module's {@link WorkspaceMembershipEraser} (ADR-0007) — the bridge lives in
 * {@code app}, not either business module, same module-graph reason {@code
 * CreateOrganizationSigningKeyBridge} already establishes.
 */
@Component
class WorkspaceMembershipEraserBridge implements WorkspaceMembershipEraser {

  private final WorkspaceMembershipRepository memberships;

  /* package */ WorkspaceMembershipEraserBridge(final WorkspaceMembershipRepository memberships) {
    this.memberships = memberships;
  }

  @Override
  public void eraseAllMembershipsFor(final AccountId accountId) {
    memberships.deleteAllByAccountId(accountId.value());
  }
}
