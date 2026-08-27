package com.clavaris.organization.application.usecase.removeworkspacemember;

/**
 * Inbound port for {@code POST /api/v1/admin/workspaces/{workspaceId}/members/{accountId}:remove}.
 */
@FunctionalInterface
public interface RemoveWorkspaceMemberUseCase {

  /**
   * @throws WorkspaceMembershipNotFoundException if no membership exists for {@code
   *     command.accountId()} in {@code command.workspaceId()}
   * @throws CannotRemoveLastAdminException if this would leave the workspace with zero ADMINs
   */
  void handle(RemoveWorkspaceMemberCommand command);
}
