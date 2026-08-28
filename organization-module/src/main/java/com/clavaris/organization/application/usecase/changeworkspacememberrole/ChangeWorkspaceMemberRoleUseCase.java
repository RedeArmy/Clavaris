package com.clavaris.organization.application.usecase.changeworkspacememberrole;

import com.clavaris.organization.domain.model.WorkspaceMembership;

/** Inbound port for {@code PUT /api/v1/admin/workspaces/{workspaceId}/members/{accountId}/role}. */
@FunctionalInterface
public interface ChangeWorkspaceMemberRoleUseCase {

  /**
   * @throws WorkspaceMembershipNotFoundException if no membership exists for {@code
   *     command.accountId()} in {@code command.workspaceId()}
   * @throws CannotDemoteLastAdminException if this would leave the workspace with zero ADMINs
   */
  WorkspaceMembership handle(ChangeWorkspaceMemberRoleCommand command);
}
