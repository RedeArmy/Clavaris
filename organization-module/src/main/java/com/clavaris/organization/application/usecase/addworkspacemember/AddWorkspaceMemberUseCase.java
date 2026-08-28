package com.clavaris.organization.application.usecase.addworkspacemember;

import com.clavaris.organization.domain.model.WorkspaceMembership;

/** Inbound port for {@code POST /api/v1/admin/workspaces/{workspaceId}/members}. */
@FunctionalInterface
public interface AddWorkspaceMemberUseCase {

  /**
   * @throws WorkspaceNotFoundException if {@code command.workspaceId()} doesn't exist
   * @throws AccountProvisioner.AccountAlreadyExistsException if {@code command.email()} is already
   *     registered in this Workspace's own Organization
   */
  WorkspaceMembership handle(AddWorkspaceMemberCommand command);
}
