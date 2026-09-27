package com.clavaris.organization.application.usecase.findworkspacemembershipforaccount;

import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.Optional;

/**
 * ADR-0029: identity-module's Users-tab "Assign role" popup needs, given only an {@code accountId},
 * which Workspace (and current role) this account already belongs to, without knowing the {@code
 * workspaceId} up front the way every other Workspace-scoped use case in this module does.
 */
@FunctionalInterface
public interface FindWorkspaceMembershipForAccountUseCase {

  /**
   * @return the account's own {@link WorkspaceMembership}, or empty if it belongs to no Workspace
   *     yet. {@link
   *     com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository#findAllByAccountId}
   *     may return more than one row if a future increment ever allows an existing Account to join
   *     a second Workspace (v1's own provisioning flow never does) — this use case takes the first,
   *     a defensive choice documented at the call site, not a silent assumption.
   */
  Optional<WorkspaceMembership> handle(FindWorkspaceMembershipForAccountQuery query);
}
